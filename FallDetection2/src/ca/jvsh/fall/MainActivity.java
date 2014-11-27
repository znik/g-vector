package ca.jvsh.fall;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
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
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity
{
	//constants
	private static final String	TAG			= "MainActivity";
	private static final int	SETTINGS	= 1;				// The request code
	private static final int	MSG_UPDATE_STATS	= 1337;
	private static final int	MSG_PRINT_FALL	= 1338;

	//menu and settings
	private Menu				mMenu;
	private boolean				mRestartOnBoot;

	//controls
	private TextView			mStatusTextView;
	private TextView			mAverageTextView;
	private TextView			mVarianceTextView;
	private TextView			mFrequencyTextView;

	private EditText			mLogEditText;

	//Service-related members
	/** The primary interface we will be calling on the service. */
	IFallDetectionService		mService	= null;
	private boolean				mIsBound;
	private ActivityDataHandler	mSensorHandler;


	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		mStatusTextView = ((TextView) findViewById(R.id.textView_status));
		mAverageTextView = ((TextView) findViewById(R.id.textView_average));
		mVarianceTextView = ((TextView) findViewById(R.id.textView_variance));
		mFrequencyTextView = ((TextView) findViewById(R.id.textView_frequency));

		mLogEditText = ((EditText) findViewById(R.id.editTextLog));

		mSensorHandler = new ActivityDataHandler(this);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		mMenu = menu;
		setStartStopMenu(!isMyServiceRunning(FallDetectionService.class));
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		switch (item.getItemId())
		{
			case R.id.menu_start:
			{
				//start service
				Intent startForegroundIntent = new Intent("ca.jvsh.fall.FALL_DETECTION_SERVICE");
				startForegroundIntent.setClass(MainActivity.this, FallDetectionService.class);
				startService(startForegroundIntent);

				//check if service is running
				if (isMyServiceRunning(FallDetectionService.class))
				{
					//set the menu
					setStartStopMenu(false);

					//update restart on boot parameters
					updateRestartOnBoot(mRestartOnBoot);

					// Establish connections with the service, binding by interface name.
					mIsBound = bindService(new Intent(IFallDetectionService.class.getName()),
							mConnection, Context.BIND_ABOVE_CLIENT | Context.BIND_AUTO_CREATE);

					if (mIsBound)
					{
						// Notify the user that we bound to service.
						Toast.makeText(this, R.string.remote_service_binding, Toast.LENGTH_SHORT).show();
					}
				}
				else
				{
					Log.e(TAG, "Could not start Fall Detection service");
				}
			}
			return true;
			case R.id.menu_stop:
			{
				//check if service is indeed running
				if (isMyServiceRunning(FallDetectionService.class))
				{
					if(mIsBound)
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
								e.printStackTrace();
							}
						}
	
						// Detach our existing connection.
						unbindService(mConnection);
						mIsBound = false;
						//tell the user what happened.
						Toast.makeText(this, R.string.remote_service_unbinding, Toast.LENGTH_SHORT).show();
					}


					// Cancel a previous call to startService().
					// Note: the service will not actually stop at this point if there are
					// still bound clients.
					Intent service = new Intent(MainActivity.this, FallDetectionService.class);
					stopService(service);
	
					updateRestartOnBoot(mRestartOnBoot);
	
					setStartStopMenu(true);
				}
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
			loadPreferences();
		}
	}

	@Override
	protected void onResume()
	{
		super.onResume();

		loadPreferences();

		if (isMyServiceRunning(FallDetectionService.class) && !mIsBound)
		{
			// Establish connections with the service, binding by interface name.
			mIsBound = bindService(new Intent(IFallDetectionService.class.getName()),
					mConnection, Context.BIND_ABOVE_CLIENT | Context.BIND_AUTO_CREATE);

			if (mIsBound)
			{
				// Notify the user that we bound to service.
				Toast.makeText(this, R.string.remote_service_binding, Toast.LENGTH_SHORT).show();
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
					e.printStackTrace();
				}
			}

			// Detach our existing connection.
			unbindService(mConnection);
			mIsBound = false;
			//tell the user what happened.
			Toast.makeText(this, R.string.remote_service_unbinding, Toast.LENGTH_SHORT).show();
		}

		super.onPause();
	}

	/**
	* Class for interacting with the main interface of the service.
	*/
	private ServiceConnection				mConnection	= new ServiceConnection()
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

				// We want to monitor the service for as long as we are connected to it.
				try
				{
					mService.registerCallback(mCallback);
				}
				catch (RemoteException e)
				{
					e.printStackTrace();
				}

				// tell the user what happened.
				Toast.makeText(MainActivity.this, R.string.remote_service_connected,
						Toast.LENGTH_SHORT).show();
			}

			public void onServiceDisconnected(ComponentName className)
			{
				// This is called when the connection with the service has been
				// unexpectedly disconnected -- that is, its process crashed.
				mService = null;

				// tell the user what happened.
				Toast.makeText(MainActivity.this, R.string.remote_service_disconnected,
						Toast.LENGTH_SHORT).show();
			}
		};

	// ----------------------------------------------------------------------
	// Code showing how to deal with callbacks.
	// ----------------------------------------------------------------------
	/**
	 * This implementation is used to receive callbacks from the remote service.
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
			public void updateStats(double average, double variance, double frequency)
			{
				Message mSensorMessage = new Message();
				mSensorMessage.what = MSG_UPDATE_STATS;
				Bundle mMessageBundle = new Bundle();

				mMessageBundle.putDouble("Average", average);
				mMessageBundle.putDouble("Variance", variance);
				mMessageBundle.putDouble("Frequency", frequency);

				mSensorMessage.setData(mMessageBundle);

				mSensorHandler.sendMessage(mSensorMessage);
			}
			
			public void fallMessage(String fallMsg)
			{
				Message mSensorMessage = new Message();
				mSensorMessage.what = MSG_PRINT_FALL;
				Bundle mMessageBundle = new Bundle();

				mMessageBundle.putString("Message", fallMsg);

				mSensorMessage.setData(mMessageBundle);

				mSensorHandler.sendMessage(mSensorMessage);
			}
			
		};

	static class ActivityDataHandler extends Handler
	{
		WeakReference<MainActivity>	mMainActivity;
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

		ActivityDataHandler(MainActivity fallDetectionActivity)
		{
			mMainActivity = new WeakReference<MainActivity>(fallDetectionActivity);
		}

		@Override
		public void handleMessage(Message msg)
		{
			MainActivity fallDetectionActivity = mMainActivity.get();
			Bundle bundle = msg.getData();

			switch (msg.what)
			{
				case MSG_UPDATE_STATS:
					fallDetectionActivity.mAverageTextView.setText( String.format("%10.5f", bundle.getDouble("Average")));
					fallDetectionActivity.mVarianceTextView.setText( String.format("%10.5f", bundle.getDouble("Variance")));
					fallDetectionActivity.mFrequencyTextView.setText(String.format("%10.5f Hz",bundle.getDouble("Frequency")));
					break;
				case MSG_PRINT_FALL:
					fallDetectionActivity.mLogEditText.append(bundle.getString("Message") + " " + sdf.format(new Date()) + "\n");
					break;
			}
		}

	}

	/*Utility functions*/

	private void loadPreferences()
	{
		SharedPreferences mySharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		mRestartOnBoot = mySharedPreferences.getBoolean("restartOnBoot", true);
		//update restart on boot conditions
		updateRestartOnBoot(mRestartOnBoot);
	}

	private void updateRestartOnBoot(boolean restartOnBoot)
	{
		PackageManager pm = getPackageManager();
		ComponentName receiver = new ComponentName(this, OnBootReceiver.class);
		
		if(isMyServiceRunning(FallDetectionService.class) && restartOnBoot)
		{
			//if service is running we restart on boot
			pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
		}
		else
		{
			//if service is not running, we do not restart on boot
			pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
		}
	}

	private void setStartStopMenu(boolean showStart)
	{
		mMenu.findItem(R.id.menu_start).setVisible(showStart);
		mMenu.findItem(R.id.menu_stop).setVisible(!showStart);
		mMenu.findItem(R.id.menu_settings).setEnabled(showStart);
		mStatusTextView.setText(showStart ? R.string.service_stopped : R.string.service_started);
	}
	
	private boolean isMyServiceRunning(Class<?> serviceClass)
	{
		ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE))
		{
			if (serviceClass.getName().equals(service.service.getClassName()))
			{
				return true;
			}
		}
		return false;
	}
}
