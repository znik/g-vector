package ca.jvsh.fall;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

public class OnBootReceiver extends BroadcastReceiver
{

	public void onReceive(Context context, Intent intent)
	{
		String action = intent.getAction();

		if (action.equals(Intent.ACTION_BOOT_COMPLETED))
		{
			Log.e("boot-status", "BOOT_COMPLETED=================================================");
			SharedPreferences mySharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
			if (mySharedPreferences.getBoolean("checkBoxPref", false))
			{
				Intent startForegroundIntent = new Intent();
				startForegroundIntent.setAction(
                        "ca.jvsh.fall.FALL_DETECTION_SERVICE");
               
				
				//Intent service = new Intent(context, FallDetectionService.class);
				ComponentName result = context.startService(startForegroundIntent);
				if (null == result){
                    // something really wrong here
                    Log.e("boot-status","Could not start service ");
                }
				//Intent updateIntent = new Intent();
				//updateIntent.setClass(context, FallDetectionService.class);

				//PendingIntent pendingIntent = PendingIntent.getService(context, 0, updateIntent, 0);
				//AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
				//alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, java.lang.System.currentTimeMillis() + 5000, 5000, pendingIntent);

			}
		}
	}
}
