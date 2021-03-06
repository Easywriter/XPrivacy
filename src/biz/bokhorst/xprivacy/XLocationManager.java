package biz.bokhorst.xprivacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.location.LocationListener;

import de.robv.android.xposed.XC_MethodHook.MethodHookParam;

public class XLocationManager extends XHook {
	private Methods mMethod;
	private String mClassName;
	private static final Map<LocationListener, XLocationListener> mListener = new WeakHashMap<LocationListener, XLocationListener>();

	private XLocationManager(Methods method, String restrictionName, String className) {
		super(restrictionName, method.name(), null);
		mMethod = method;
		mClassName = className;
	}

	private XLocationManager(Methods method, String restrictionName, String className, int sdk) {
		super(restrictionName, method.name(), null, sdk);
		mMethod = method;
		mClassName = className;
	}

	public String getClassName() {
		return mClassName;
	}

	// @formatter:off

	// public void addGeofence(LocationRequest request, Geofence fence, PendingIntent intent)
	// public boolean addNmeaListener(GpsStatus.NmeaListener listener)
	// public void addProximityAlert(double latitude, double longitude, float radius, long expiration, PendingIntent intent)
	// public Location getLastKnownLocation(String provider)
	// public List<String> getProviders(boolean enabledOnly)
	// public List<String> getProviders(Criteria criteria, boolean enabledOnly)
	// public boolean isProviderEnabled(String provider)
	// public void removeUpdates(LocationListener listener)
	// public void removeUpdates(PendingIntent intent)
	// public void requestLocationUpdates(String provider, long minTime, float minDistance, LocationListener listener)
	// public void requestLocationUpdates(String provider, long minTime, float minDistance, LocationListener listener, Looper looper)
	// public void requestLocationUpdates(long minTime, float minDistance, Criteria criteria, LocationListener listener, Looper looper)
	// public void requestLocationUpdates(String provider, long minTime, float minDistance, PendingIntent intent)
	// public void requestLocationUpdates(long minTime, float minDistance, Criteria criteria, PendingIntent intent)
	// public void requestSingleUpdate(String provider, LocationListener listener, Looper looper)
	// public void requestSingleUpdate(Criteria criteria, LocationListener listener, Looper looper)
	// public void requestSingleUpdate(String provider, PendingIntent intent)
	// public void requestSingleUpdate(Criteria criteria, PendingIntent intent)
	// public boolean sendExtraCommand(String provider, String command, Bundle extras)
	// frameworks/base/location/java/android/location/LocationManager.java
	// http://developer.android.com/reference/android/location/LocationManager.html

	// @formatter:on

	private enum Methods {
		addNmeaListener, addProximityAlert, getLastKnownLocation, getProviders, isProviderEnabled, removeUpdates, requestLocationUpdates, requestSingleUpdate, sendExtraCommand, addGeofence, getLastLocation
	};

	public static List<XHook> getInstances(Object instance) {
		String className = instance.getClass().getName();
		List<XHook> listHook = new ArrayList<XHook>();
		for (Methods loc : Methods.values())
			if (loc != Methods.addGeofence && loc != Methods.getLastLocation)
				listHook.add(new XLocationManager(loc, PrivacyManager.cLocation, className));
		listHook.add(new XLocationManager(Methods.addGeofence, PrivacyManager.cLocation, className,
				Build.VERSION_CODES.JELLY_BEAN_MR1));
		listHook.add(new XLocationManager(Methods.getLastLocation, PrivacyManager.cLocation, className,
				Build.VERSION_CODES.JELLY_BEAN_MR1));
		return listHook;
	}

	@Override
	protected void before(MethodHookParam param) throws Throwable {
		if (mMethod == Methods.addNmeaListener) {
			if (isRestricted(param))
				param.setResult(false);
		} else if (mMethod == Methods.addGeofence || mMethod == Methods.addProximityAlert) {
			if (isRestricted(param))
				param.setResult(null);
		} else if (mMethod == Methods.removeUpdates) {
			if (isRestricted(param))
				removeLocationListener(param);
		} else if (mMethod == Methods.requestLocationUpdates) {
			if (isRestricted(param))
				replaceLocationListener(param, 3);
		} else if (mMethod == Methods.requestSingleUpdate) {
			if (isRestricted(param))
				replaceLocationListener(param, 1);
		}
	}

	@Override
	protected void after(MethodHookParam param) throws Throwable {
		if (mMethod != Methods.addGeofence && mMethod != Methods.addNmeaListener
				&& mMethod != Methods.addProximityAlert && mMethod != Methods.removeUpdates
				&& mMethod != Methods.requestLocationUpdates && mMethod != Methods.requestSingleUpdate)
			if (mMethod == Methods.isProviderEnabled) {
				if (isRestricted(param))
					param.setResult(false);
			} else if (mMethod == Methods.getLastLocation || mMethod == Methods.getLastKnownLocation) {
				Location location = (Location) param.getResult();
				if (location != null && isRestricted(param))
					param.setResult(PrivacyManager.getDefacedLocation(Binder.getCallingUid(), location));
			} else if (mMethod == Methods.getProviders) {
				if (param.getResult() != null && isRestricted(param))
					param.setResult(new ArrayList<String>());
			} else if (mMethod == Methods.sendExtraCommand) {
				if (isRestricted(param))
					param.setResult(false);
			} else
				Util.log(this, Log.WARN, "Unknown method=" + param.method.getName());
	}

	private void replaceLocationListener(MethodHookParam param, int arg) throws Throwable {
		if (param.args.length > arg && param.args[arg] != null
				&& LocationListener.class.isAssignableFrom(param.args[arg].getClass())) {
			if (!(param.args[arg] instanceof XLocationListener)) {
				LocationListener listener = (LocationListener) param.args[arg];
				if (listener != null) {
					XLocationListener xListener = new XLocationListener(listener);
					synchronized (mListener) {
						mListener.put(listener, xListener);
						Util.log(this, Log.INFO, "Added count=" + mListener.size() + " uid=" + Binder.getCallingUid());
					}
					param.args[arg] = xListener;
				}
			}
		} else
			// Intent
			param.setResult(null);
	}

	private void removeLocationListener(MethodHookParam param) {
		if (param.args.length > 0 && param.args[0] != null
				&& LocationListener.class.isAssignableFrom(param.args[0].getClass())) {
			LocationListener listener = (LocationListener) param.args[0];
			synchronized (mListener) {
				XLocationListener xlistener = mListener.get(listener);
				if (xlistener == null)
					Util.log(this, Log.WARN, "Not found count=" + mListener.size() + " uid=" + Binder.getCallingUid());
				else {
					param.args[0] = xlistener;
					mListener.remove(listener);
				}
			}
		} else
			// Intent
			param.setResult(null);
	}

	private class XLocationListener implements LocationListener {
		private LocationListener mLocationListener;

		public XLocationListener(LocationListener locationListener) {
			mLocationListener = locationListener;
		}

		@Override
		public void onLocationChanged(Location location) {
			mLocationListener.onLocationChanged(location == null ? location : PrivacyManager.getDefacedLocation(
					Binder.getCallingUid(), location));
		}

		@Override
		public void onProviderDisabled(String provider) {
			mLocationListener.onProviderDisabled(provider);
		}

		@Override
		public void onProviderEnabled(String provider) {
			mLocationListener.onProviderEnabled(provider);
		}

		@Override
		public void onStatusChanged(String provider, int status, Bundle extras) {
			mLocationListener.onStatusChanged(provider, status, extras);
		}
	}
}
