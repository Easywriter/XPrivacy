package biz.bokhorst.xprivacy;

import java.util.ArrayList;
import java.util.List;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

public class UpdateService extends Service {
	public static String cAction = "Action";
	public static int cActionBoot = 1;
	public static int cActionUpdated = 2;

	private static Thread mWorkerThread;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onTrimMemory(int level) {
		Util.log(null, Log.WARN, "Service received trim memory level=" + level);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// Check if work
		if (intent == null) {
			stopSelf();
			return 0;
		}

		// Check action
		Bundle extras = intent.getExtras();
		if (extras.containsKey(cAction)) {
			final int action = extras.getInt(cAction);
			Util.log(null, Log.WARN, "Service received action=" + action + " flags=" + flags);

			// Check service
			if (PrivacyService.getClient() == null) {
				Util.log(null, Log.ERROR, "Service not available");
				stopSelf();
				return 0;
			}

			// Start foreground service
			NotificationCompat.Builder builder = new NotificationCompat.Builder(UpdateService.this);
			builder.setSmallIcon(R.drawable.ic_launcher);
			builder.setContentTitle(getString(R.string.app_name));
			builder.setContentText(getString(R.string.msg_service));
			builder.setWhen(System.currentTimeMillis());
			builder.setAutoCancel(false);
			builder.setOngoing(true);
			Notification notification = builder.build();
			startForeground(Util.NOTIFY_SERVICE, notification);

			// Start worker
			mWorkerThread = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						// Check action
						if (action == cActionBoot) {
							// Boot received
							List<ApplicationInfo> listApp = UpdateService.this.getPackageManager()
									.getInstalledApplications(0);

							// Start migrate
							int first = 0;
							String format = getString(R.string.msg_migrating);
							PrivacyProvider.migrateLegacy(UpdateService.this);

							// Migrate global settings
							PrivacyManager.setSettingList(PrivacyProvider.migrateSettings(UpdateService.this, 0));
							PrivacyProvider.finishMigrateSettings(0);

							// Migrate application settings/restrictions
							for (int i = 1; i <= listApp.size(); i++) {
								int uid = listApp.get(i - 1).uid;
								// Settings
								List<ParcelableSetting> listSetting = PrivacyProvider.migrateSettings(
										UpdateService.this, uid);
								PrivacyManager.setSettingList(listSetting);
								PrivacyProvider.finishMigrateSettings(uid);

								// Restrictions
								List<ParcelableRestriction> listRestriction = PrivacyProvider.migrateRestrictions(
										UpdateService.this, uid);
								PrivacyManager.setRestrictionList(listRestriction);
								PrivacyProvider.finishMigrateRestrictions(uid);

								if (first == 0)
									if (listSetting.size() > 0 || listRestriction.size() > 0)
										first = i;
								if (first > 0)
									notifyProgress(UpdateService.this, Util.NOTIFY_MIGRATE, format, 100 * (i - first)
											/ (listApp.size() - first));
							}
							if (first == 0)
								Util.log(null, Log.WARN, "Nothing to migrate");

							// Complete migration
							PrivacyService.getClient().migrated();

							// Randomize
							first = 0;
							format = getString(R.string.msg_randomizing);

							// Randomize global
							PrivacyManager.setSettingList(getRandomizeWork(UpdateService.this, 0));

							// Randomize applications
							for (int i = 1; i <= listApp.size(); i++) {
								int uid = listApp.get(i - 1).uid;
								List<ParcelableSetting> listSetting = getRandomizeWork(UpdateService.this, uid);
								PrivacyManager.setSettingList(listSetting);

								if (first == 0)
									if (listSetting.size() > 0)
										first = i;
								if (first > 0)
									notifyProgress(UpdateService.this, Util.NOTIFY_RANDOMIZE, format, 100 * (i - first)
											/ (listApp.size() - first));
							}
							if (first == 0)
								Util.log(null, Log.WARN, "Nothing to randomize");

							// Done
							stopForeground(true);
							stopSelf();
						} else if (action == cActionUpdated) {
							// Self updated

							// Get previous version
							PackageManager pm = UpdateService.this.getPackageManager();
							PackageInfo pInfo = pm.getPackageInfo(UpdateService.this.getPackageName(), 0);
							Version sVersion = new Version(PrivacyManager.getSetting(null, 0,
									PrivacyManager.cSettingVersion, "0.0", false));

							// Upgrade packages
							if (sVersion.compareTo(new Version("0.0")) != 0) {
								Util.log(null, Log.WARN, "Starting upgrade from version " + sVersion + " to version "
										+ pInfo.versionName);
								boolean dangerous = PrivacyManager.getSettingBool(null, 0,
										PrivacyManager.cSettingDangerous, false, false);
								List<ApplicationInfo> listApp = UpdateService.this.getPackageManager()
										.getInstalledApplications(0);

								int first = 0;
								String format = getString(R.string.msg_upgrading);

								for (int i = 1; i <= listApp.size(); i++) {
									int uid = listApp.get(i - 1).uid;
									List<ParcelableRestriction> listRestriction = getUpgradeWork(sVersion, dangerous,
											uid);
									PrivacyManager.setRestrictionList(listRestriction);

									if (first == 0)
										if (listRestriction.size() > 0)
											first = i;
									if (first > 0)
										notifyProgress(UpdateService.this, Util.NOTIFY_UPGRADE, format, 100
												* (i - first) / (listApp.size() - first));
								}
								if (first == 0)
									Util.log(null, Log.WARN, "Nothing to upgrade version=" + sVersion);
							} else
								Util.log(null, Log.WARN, "Noting to upgrade version=" + sVersion);

							PrivacyManager.setSetting(null, 0, PrivacyManager.cSettingVersion, pInfo.versionName);

							// Done
							stopForeground(true);
							stopSelf();
						} else {
							Util.log(null, Log.ERROR, "Unknown action=" + action);

							// Done
							stopForeground(true);
							stopSelf();
						}
					} catch (Throwable ex) {
						Util.bug(null, ex);
						// Leave service running
					}
				}
			});
			mWorkerThread.start();
		} else
			Util.log(null, Log.ERROR, "Action missing");

		return START_STICKY;
	}

	private static List<ParcelableSetting> getRandomizeWork(Context context, int uid) {
		List<ParcelableSetting> listWork = new ArrayList<ParcelableSetting>();
		if (PrivacyManager.getSettingBool(null, uid, PrivacyManager.cSettingRandom, false, true)) {
			listWork.add(new ParcelableSetting(uid, PrivacyManager.cSettingLatitude, PrivacyManager
					.getRandomProp("LAT")));
			listWork.add(new ParcelableSetting(uid, PrivacyManager.cSettingLongitude, PrivacyManager
					.getRandomProp("LON")));
			listWork.add(new ParcelableSetting(uid, PrivacyManager.cSettingSerial, PrivacyManager
					.getRandomProp("SERIAL")));
			listWork.add(new ParcelableSetting(uid, PrivacyManager.cSettingMac, PrivacyManager.getRandomProp("MAC")));
			listWork.add(new ParcelableSetting(uid, PrivacyManager.cSettingPhone, PrivacyManager.getRandomProp("PHONE")));
			listWork.add(new ParcelableSetting(uid, PrivacyManager.cSettingImei, PrivacyManager.getRandomProp("IMEI")));
			listWork.add(new ParcelableSetting(uid, PrivacyManager.cSettingId, PrivacyManager
					.getRandomProp("ANDROID_ID")));
			listWork.add(new ParcelableSetting(uid, PrivacyManager.cSettingGsfId, PrivacyManager
					.getRandomProp("GSF_ID")));
			listWork.add(new ParcelableSetting(uid, PrivacyManager.cSettingAdId, PrivacyManager
					.getRandomProp("AdvertisingId")));
			listWork.add(new ParcelableSetting(uid, PrivacyManager.cSettingCountry, PrivacyManager
					.getRandomProp("ISO3166")));
			listWork.add(new ParcelableSetting(uid, PrivacyManager.cSettingSubscriber, PrivacyManager
					.getRandomProp("SubscriberId")));
			listWork.add(new ParcelableSetting(uid, PrivacyManager.cSettingSSID, PrivacyManager.getRandomProp("SSID")));
		}
		return listWork;
	}

	private static List<ParcelableRestriction> getUpgradeWork(Version sVersion, boolean dangerous, int uid) {
		List<ParcelableRestriction> listWork = new ArrayList<ParcelableRestriction>();
		for (String restrictionName : PrivacyManager.getRestrictions())
			for (Hook md : PrivacyManager.getHooks(restrictionName))
				if (md.getFrom() != null)
					if (sVersion.compareTo(md.getFrom()) < 0) {
						// Disable new dangerous restrictions
						if (!dangerous && md.isDangerous()) {
							Util.log(null, Log.WARN, "Upgrading dangerous " + md + " from=" + md.getFrom() + " uid="
									+ uid);
							listWork.add(new ParcelableRestriction(uid, md.getRestrictionName(), md.getName(), false));
						}

						// Restrict replaced methods
						if (md.getReplaces() != null)
							if (PrivacyManager.getRestriction(null, uid, md.getRestrictionName(), md.getReplaces(),
									false, false, null)) {
								Util.log(null, Log.WARN,
										"Replaced " + md.getReplaces() + " by " + md + " from=" + md.getFrom()
												+ " uid=" + uid);
								listWork.add(new ParcelableRestriction(uid, md.getRestrictionName(), md.getName(), true));
							}
					}
		return listWork;
	}

	private static void notifyProgress(Context context, int id, String format, int percentage) {
		String message = String.format(format, String.format("%d %%", percentage));
		Util.log(null, Log.WARN, message);

		NotificationManager notificationManager = (NotificationManager) context
				.getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(context);
		builder.setSmallIcon(R.drawable.ic_launcher);
		builder.setContentTitle(context.getString(R.string.app_name));
		builder.setContentText(message);
		builder.setWhen(System.currentTimeMillis());
		builder.setAutoCancel(percentage == 100);
		builder.setOngoing(percentage < 100);
		Notification notification = builder.build();
		notificationManager.notify(id, notification);
	}
}
