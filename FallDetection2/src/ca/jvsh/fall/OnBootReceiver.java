package ca.jvsh.fall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

public class OnBootReceiver extends BroadcastReceiver
{
	private static final String	TAG	= "OnBootReceiver";

	public void onReceive(Context context, Intent intent)
	{
		if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED))
		{
			Log.d(TAG, "Fall Detection 2.0 - boot completed");
			SharedPreferences mySharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
			if (mySharedPreferences.getBoolean("restartOnBoot", true))
			{
				Log.d(TAG, "Starting Fall Detection service");
				
				Intent startForegroundIntent = new Intent("ca.jvsh.fall.FALL_DETECTION_SERVICE");
				if (context.startService(startForegroundIntent) == null)
				{
					// something really wrong here
					Log.e(TAG, "Could not start Fall Detection service");
				}
			}
			else
			{
				Log.d(TAG, "Restart of the Fall Detection service on boot is disabled in settings");
			}
		}
	}
}
