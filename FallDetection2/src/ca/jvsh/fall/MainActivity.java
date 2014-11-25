package ca.jvsh.fall;

import java.lang.ref.WeakReference;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

public class MainActivity extends Activity
{

	static final int	SETTINGS	= 1;		// The request code

	boolean				running		= false;
	private Menu		mMenu;

	/** The primary interface we will be calling on the service. */
	IFallDetectionService				mService		= null;
	
	private boolean				mIsBound;
	
	
	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		mMenu = menu;
		setStartStopMenu(!running);
		return true;
	}

	private void setStartStopMenu(boolean showStart)
	{
		mMenu.findItem(R.id.menu_start).setVisible(showStart);
		mMenu.findItem(R.id.menu_stop).setVisible(!showStart);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		switch (id)
		{
			case R.id.menu_start:
			{
				setStartStopMenu(false);
				Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show();
				PackageManager pm = getPackageManager();
				ComponentName receiver = new ComponentName(this, OnBootReceiver.class);
				pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);

				// Make sure the service is started.  It will continue running
				// until someone calls stopService().
				// We use an action code here, instead of explictly supplying
				// the component name, so that other packages can replace
				// the service.
				//startService(new Intent("ca.jvsh.falldetectionlogger.REMOTE_SERVICE"));
				
				
				Intent startForegroundIntent = new Intent(
                        "ca.jvsh.fall.FALL_DETECTION_SERVICE");
                startForegroundIntent.setClass(
                        MainActivity.this, FallDetectionService.class);
                startService(startForegroundIntent);
				//Intent service = new Intent(MainActivity.this, FallDetectionService.class);
                //startForegroundIntent.setClass(
                //        ForegroundActivity.this, MyForegroundService.class);
                //startService(service);
                
				/*Notification notification = new Notification(R.drawable.icon, getText(R.string.ticker_text),
				        System.currentTimeMillis());
				Intent notificationIntent = new Intent(this, ExampleActivity.class);
				PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
				notification.setLatestEventInfo(this, getText(R.string.notification_title),
				        getText(R.string.notification_message), pendingIntent);
				startForeground(ONGOING_NOTIFICATION_ID, notification);*/

				if (isMyServiceRunning(FallDetectionService.class))
				{
					// Establish a couple connections with the service, binding
					// by interface names.  This allows other applications to be
					// installed that replace the remote service by implementing
					// the same interface.
					mIsBound = bindService(new Intent(IFallDetectionService.class.getName()),
							mConnection, Context.BIND_ABOVE_CLIENT | Context.BIND_AUTO_CREATE);

					if (mIsBound)
					{
						//mCallbackText.setText(R.string.remote_service_binding);
						// As part of the sample, tell the user what happened.
						Toast.makeText(this, R.string.remote_service_binding,
								Toast.LENGTH_SHORT).show();
						//mTimeStampsList.fill(0, COUNTS, 0);
					}
				}
			}
			return true;
			case R.id.menu_stop:
			{
				setStartStopMenu(true);
				Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show();

				PackageManager pm = getPackageManager();
				ComponentName receiver = new ComponentName(this, OnBootReceiver.class);
				pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
			
				if (isMyServiceRunning(FallDetectionService.class) && mIsBound)
				{

					// If we have received the service, and hence registered with
					// it, then now is the time to unregister.
					if (mService != null)
					{
						try
						{
							mService.unregisterCallback(mCallback);
						}
						catch (RemoteException e)
						{
							// There is nothing special we need to do if the service
							// has crashed.
						}
					}

					// Detach our existing connection.
					unbindService(mConnection);
					mIsBound = false;
					//mCallbackText.setText(R.string.remote_service_unbinding);

					// As part of the sample, tell the user what happened.
					Toast.makeText(this, R.string.remote_service_unbinding,
							Toast.LENGTH_SHORT).show();

				}
				// Cancel a previous call to startService().  Note that the
				// service will not actually stop at this point if there are
				// still bound clients.
				//stopService(new Intent("ca.jvsh.falldetectionlogger.REMOTE_SERVICE"));
				Intent service = new Intent(MainActivity.this, FallDetectionService.class);
				stopService(service);
			}
			return true;
			case R.id.menu_settings:
			{
				Intent intent = new Intent(this, SettingsActivity.class);
				startActivityForResult(intent, SETTINGS);
			}
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data)
	{
		// Check which request it is that we're responding to
		if (requestCode == SETTINGS)
		{
			loadPref();
		}
	}

	private void loadPref()
	{
		SharedPreferences mySharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

		boolean checkBoxPref = mySharedPreferences.getBoolean("checkBoxPref", false);

		Toast.makeText(this, checkBoxPref + "", Toast.LENGTH_SHORT).show();

	}
	

	@Override
	protected void onResume()
	{
		super.onResume();

		if (isMyServiceRunning(FallDetectionService.class))
		{
			// Establish a couple connections with the service, binding
			// by interface names.  This allows other applications to be
			// installed that replace the remote service by implementing
			// the same interface.
			mIsBound = bindService(new Intent(IFallDetectionService.class.getName()),
					mConnection, 0);

			if (mIsBound)
			{
				//mCallbackText.setText(R.string.remote_service_binding);
				// As part of the sample, tell the user what happened.
				Toast.makeText(MainActivity.this, R.string.remote_service_binding,
						Toast.LENGTH_SHORT).show();
				//mTimeStampsList.fill(0, COUNTS, 0);
			}
		}
	}

	@Override
	protected void onPause()
	{
		if (isMyServiceRunning(FallDetectionService.class) && mIsBound)
		{
			// If we have received the service, and hence registered with
			// it, then now is the time to unregister.
			if (mService != null)
			{
				try
				{
					mService.unregisterCallback(mCallback);
				}
				catch (RemoteException e)
				{
					// There is nothing special we need to do if the service
					// has crashed.
				}
			}

			// Detach our existing connection.
			unbindService(mConnection);
			mIsBound = false;
			//mCallbackText.setText(R.string.remote_service_unbinding);

			// As part of the sample, tell the user what happened.
			Toast.makeText(MainActivity.this, R.string.remote_service_unbinding,
					Toast.LENGTH_SHORT).show();

		}
		super.onPause();

	}

	/*
	* Class for interacting with the main interface of the service.
	*/
	private ServiceConnection		mConnection	= new ServiceConnection()
												{
													public void onServiceConnected(ComponentName className,
															IBinder service)
													{
														// This is called when the connection with the service has been
														// established, giving us the service object we can use to
														// interact with the service.  We are communicating with our
														// service through an IDL interface, so get a client-side
														// representation of that from the raw service object.
														mService = IFallDetectionService.Stub.asInterface(service);

														//mCallbackText.setText("Attached.");

														// We want to monitor the service for as long as we are
														// connected to it.
														try
														{
															mService.registerCallback(mCallback);
														}
														catch (RemoteException e)
														{
															// In this case the service has crashed before we could even
															// do anything with it; we can count on soon being
															// disconnected (and then reconnected if it can be restarted)
															// so there is no need to do anything here.
														}

														// As part of the sample, tell the user what happened.
														Toast.makeText(MainActivity.this, R.string.remote_service_connected,
																Toast.LENGTH_SHORT).show();
													}

													public void onServiceDisconnected(ComponentName className)
													{
														// This is called when the connection with the service has been
														// unexpectedly disconnected -- that is, its process crashed.
														mService = null;

														//mCallbackText.setText("Disconnected.");

														// As part of the sample, tell the user what happened.
														Toast.makeText(MainActivity.this, R.string.remote_service_disconnected,
																Toast.LENGTH_SHORT).show();
													}
												};

	// ----------------------------------------------------------------------
	// Code showing how to deal with callbacks.
	// ----------------------------------------------------------------------
	private ActivityDataHandler		mSensorHandler;
	/**
	 * This implementation is used to receive callbacks from the remote
	 * service.
	 */
	private IFallDetectionServiceCallback	mCallback	= new IFallDetectionServiceCallback.Stub()
												{
													/**
													 * This is called by the remote service regularly to tell us about
													 * new values.  Note that IPC calls are dispatched through a thread
													 * pool running in each process, so the code executing here will
													 * NOT be running in our main thread like most other things -- so,
													 * to update the UI, we need to use a Handler to hop over there.
													 */
													public void sensorChanged(double resultant, long timestamp)
													{

														Message mSensorMessage = new Message();
														Bundle mMessageBundle = new Bundle();

														mMessageBundle.putDouble("Resultant", resultant);
														mMessageBundle.putLong("Timestamp", timestamp);

														mSensorMessage.setData(mMessageBundle);

														mSensorHandler.sendMessage(mSensorMessage);
													}
												};

	static class ActivityDataHandler extends Handler
	{
		WeakReference<MainActivity>	mMainActivity;

		ActivityDataHandler(MainActivity fallDetectionActivity)
		{
			mMainActivity = new WeakReference<MainActivity>(fallDetectionActivity);
		}

		@Override
		public void handleMessage(Message msg)
		{
			synchronized (this)
			{
				//MainActivity fallDetectionActivity = mMainActivity.get();

				//Bundle bundle = msg.getData();

				/*float values[] = new float[AXES];
				values[0] = bundle.getFloat("X");
				values[1] = bundle.getFloat("Y");
				values[2] = bundle.getFloat("Z");

				fallDetectionActivity.mTimeStampsList.removeAt(0);
				fallDetectionActivity.mTimeStampsList.add(bundle.getLong("Timestamp"));

				if (fallDetectionActivity.mUpdateCounter++ % UPDATE_COUNTER == 0)
				{
					fallDetectionActivity.mAccelerometerText.setText(String.format("Acceleration: X %6.3f Y %6.3f Z %6.3f", values[0], values[1], values[2]));

					fallDetectionActivity.mCallbackText .setText(String.format(
									"Frequency: %7.3f Hz", COUNTS / ((double) (fallDetectionActivity.mTimeStampsList.get(COUNTS - 1) - fallDetectionActivity.mTimeStampsList.get(0)) / 1000000000.0)));
				}*/

			}

		}

	}

	/*private boolean isFallDetectionServiceRunning()
	{
		ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE))
		{
			Log.i(MainActivity.class.getName(), " " + service.service.getClassName());
			if (FallDetectionService.class.getName().equals(service.service.getClassName()))
			{

				return true;
			}
		}
		return false;
	}*/
	
	private boolean isMyServiceRunning(Class<?> serviceClass) {
	    ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
	    for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
	        if (serviceClass.getName().equals(service.service.getClassName())) {
	            return true;
	        }
	    }
	    return false;
	}
}
