package ca.jvsh.fall;

import gnu.trove.list.TDoubleList;
import gnu.trove.list.TLongList;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.list.array.TLongArrayList;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import libsvm.svm;
import libsvm.svm_model;
import libsvm.svm_node;
import android.app.Notification;
//import android.app.Notification;
//import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.speech.tts.TextToSpeech;
//import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.Toast;

public class FallDetectionService extends Service implements SensorEventListener
{

	int NOTIFICATION_ID = 101;
	int REQUEST_CODE = 1;
	
	private SensorManager								mSensorManager;
	
	private PowerManager.WakeLock						wakeLock;

	final RemoteCallbackList<IFallDetectionServiceCallback>	mCallbacks	= new RemoteCallbackList<IFallDetectionServiceCallback>();

	//NotificationManager									mNM;

	//private BufferedWriter								mOutputLinearAcceleration;
	
	
	//consts and magic numbers
	//private static final int	AXES						= 3;
	//private static final String	AXES_NAMES[]				= { "X: ", "Y: ", "Z: " };
	private static final int	COUNTS						= 234;
	private static final int	MIN							= 0;
	private static final int	MAX							= 1;
	private static final int	RANGE						= 2;


	//private static final int	SVM_INPUTS					= 12;
	private static final int	SVM_INPUTS					= 2;
	//private static final int	SVM_INPUTS					= 3;
	//private static final int	SVM_INPUT_MEAN				= 0;
	//private static final int	SVM_INPUT_MIN				= 3;
	//private static final int	SVM_INPUT_MAX				= 6;
	//private static final int	SVM_INPUT_RANGE				= 9;

	protected static final int	MSG_SENSOR					= 1;

	protected static final int	UPDATE_COUNTER				= 10;

	private static final String	TAG							= "FallDetectionUI";

	private static final String	ACTIVITIES[]				= { "ADL ", "Fall " };
	private static final String	ACTIVITIES_ADL_CLASSES[]	= { "Normal walk", "Standing quietly", "Standing to sitting", "Standing to lying",
															"Sit to stand", "Reach and pick", "Ascend stairs", "Descend stairs" };
	private static final String	ACTIVITIES_FALL_CLASSES[]	= { "Bump", "Misstep", "Incorrect stand to sit", "Incorrect sit to stand", "Collapse", "Slip",
															"Trip" };

	//Text views
	//private TextView			mStatusTextView;
	/*private TextView			mMaxTextView;
	private TextView			mMinTextView;
	private TextView			mRangeTextView;*/
	//private TextView			mAverageTextView;
	//private TextView			mVarianceTextView;
	//private TextView			mFrequencyTextView;

	//Radio buttons
	//private RadioGroup			mSensorTypeRadioGroup;

	//TalkBack
	private CheckBox			mTalkBackCheckBox;
	private TextToSpeech		mTts;

	//Beep
	private CheckBox			mBeepCheckBox;
	private ToneGenerator		mToneGenerator;

	//Logger
	//private TextView			mCurrentState;
	//private EditText			mLogEditText;

	//Sensors
	//private SensorManager		mSensorManager;
	//private Sensor				mAccelerometer;
	private Sensor				mLinearAcceleration;
	//private Sensor				mGyroscope;
	//private SensorDataHandler	mSensorHandler;

	//flags
	private boolean				mTalkBack;
	private boolean				mBeep;

	//lists
	//private TFloatList			mElementsList[]				= new TFloatList[AXES];
	//private TIntList			mMinMaxIndicesList[][]		= new TIntList[AXES][2];
	//private double				mRanges[]					= new double[AXES];
	//private float				mTotal[]					= new float[AXES];
	private TDoubleList			mElementsList;
	//private TIntList			mMinMaxIndicesList[]		= new TIntList[2];
	//private double				mRanges;
	private double				mMean;
	private double              mAccVar;
	private double				mVariance;
	private TLongList			mTimeStampsList;

	//this variable is to reduce frequency of the screen updates - we don't need it to update text field values so often
	private int					mUpdateCounter;

	//svm
	private svm_model			mCombinedFallDetectionModel;
	private svm_model			mClassifiedFallDetectionModel;
	private double				SvmPredictionCombined, SvmPredictionClassified;
	private double				SvmPredictionCombinedPrev, SvmPredictionClassifiedPrev;

	private boolean				mNotFirst;

	private double				mGlobalMinMaxRange[][]		= new double[SVM_INPUTS][3];
	
	public void onCreate()
	{
		super.onCreate();

		//mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

		//mDisplay = ((WindowManager) this.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
		// Load settings
		acquireWakeLock();

		mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

		// Display a notification about us starting.
		//showNotification();
		
		mToneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);

		
		mElementsList = new TDoubleArrayList(COUNTS);

		mTimeStampsList = new TLongArrayList(COUNTS);

		try
		{

			BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open("model_combined_resultant_2legs.txt")));

			mCombinedFallDetectionModel = svm.svm_load_model(br);
			mCombinedFallDetectionModel.param.degree = 3;
			mCombinedFallDetectionModel.param.C = 4096;
			mCombinedFallDetectionModel.param.nu = 0.5;
			mCombinedFallDetectionModel.param.cache_size = 100;
			mCombinedFallDetectionModel.param.eps = 1e-3;
			mCombinedFallDetectionModel.param.p = 0.1;
			mCombinedFallDetectionModel.param.shrinking = 1;

			br = new BufferedReader(new InputStreamReader(getAssets().open("model_classified_resultant_2legs.txt")));

			mClassifiedFallDetectionModel = svm.svm_load_model(br);
			mClassifiedFallDetectionModel.param.degree = 3;
			mClassifiedFallDetectionModel.param.C = 4096;
			mClassifiedFallDetectionModel.param.nu = 0.5;
			mClassifiedFallDetectionModel.param.cache_size = 100;
			mClassifiedFallDetectionModel.param.eps = 1e-3;
			mClassifiedFallDetectionModel.param.p = 0.1;
			mClassifiedFallDetectionModel.param.shrinking = 1;
		}
		catch (IOException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

//		mGlobalMinMaxRange[0][MIN] = 6.1658497089701;
//		mGlobalMinMaxRange[1][MIN] = 5.25748753887043;
//		mGlobalMinMaxRange[2][MIN] = 3.856354047;
//		
//		mGlobalMinMaxRange[0][MAX] =  -2.92417878471761;
//		mGlobalMinMaxRange[1][MAX] =  -12.51066721;
//		mGlobalMinMaxRange[2][MAX] =  -4.241621014;
		mGlobalMinMaxRange[0][MIN] = 0;
		mGlobalMinMaxRange[1][MIN] = 0;

		mGlobalMinMaxRange[0][MAX] = 18.07792331;
		mGlobalMinMaxRange[1][MAX] = 166.5527461;

//		for (int i = 3; i < 6; i++)
//		{
//			mGlobalMinMaxRange[i][MIN] = -39.2266;
//			mGlobalMinMaxRange[i][MAX] = 0;
//		}
//		
//		for (int i = 6; i < 9; i++)
//		{
//			mGlobalMinMaxRange[i][MIN] = 0;
//			mGlobalMinMaxRange[i][MAX] =  39.2266;
//		}
//							
//		for (int i = 9; i < SVM_INPUTS; i++)
//		{
//			mGlobalMinMaxRange[i][MIN] = 0;
//			mGlobalMinMaxRange[i][MAX] = 78.4532;
//		}

		for (int i = 0; i < SVM_INPUTS; i++)
			mGlobalMinMaxRange[i][RANGE] = mGlobalMinMaxRange[i][MAX] - mGlobalMinMaxRange[i][MIN];

		
	}
	
	   //    build the notification which includes a pending intent
    private Notification getCompatNotification() {
        Notification.Builder builder = new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Service Started")
                .setTicker("Music Playing")
                .setWhen(System.currentTimeMillis())
                .setOngoing(true);
       
        Intent startIntent = new Intent(getApplicationContext(),
                MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, REQUEST_CODE, startIntent, 0);
        builder.setContentIntent(contentIntent);
        Notification notification = builder.build();
        return notification;
    }

	public int onStartCommand(Intent intent, int flags, int startId)
	{
		Log.i("LocalService", "Received start id " + startId + ": " + intent);
		
		startForeground(NOTIFICATION_ID, getCompatNotification());

		mSensorManager.registerListener(this,
				mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
				10000);

		Date lm = new Date();
		
//		mOutputLinearAcceleration = null;
//		String fileName = "FallDetectionLogger_linear_acceleration" + new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(lm) + ".csv";
//		try
//		{
//			File configFile = new File(Environment.getExternalStorageDirectory().getPath(), fileName);
//			FileWriter fileWriter = new FileWriter(configFile);
//			mOutputLinearAcceleration = new BufferedWriter(fileWriter);
//		}
//		catch (IOException ex)
//		{
//			Log.e(FallDetectionService.class.getName(), ex.toString());
//		}
//
//		try
//		{
//			mOutputLinearAcceleration.write("X, Y, Z, Timestamp, ");
//			mOutputLinearAcceleration.newLine();
//		}
//		catch (IOException e)
//		{
//			e.printStackTrace();
//		}

		try
		{
			mBinder.registerCallback(mSelfCallback);
		}
		catch (RemoteException e)
		{
			// In this case the service has crashed before we could even
			// do anything with it; we can count on soon being
			// disconnected (and then reconnected if it can be restarted)
			// so there is no need to do anything here.
		}
		
		

		Toast.makeText(this, "Starting receiving data", Toast.LENGTH_SHORT).show();
		//mLogEditText.setText("");

		/*for (int i = 0; i < AXES; i++)
		{
			mElementsList[i].fill(0, COUNTS, 0);
			for (int j = 0; j < 2; j++)
			{
				mMinMaxIndicesList[i][j].clear();
				mMinMaxIndicesList[i][j].add(COUNTS - 1);
			}
			mTotal[i] = 0;
		}*/
		
		mElementsList.fill(0, COUNTS, 0);
		
		mTimeStampsList.fill(0, COUNTS, 0);

		mMean = 0.0;
		mAccVar = 0.0;
		mVariance = 0.0;
		
		mNotFirst = false;

		/*switch (mSensorTypeRadioGroup.getCheckedRadioButtonId())
		{
			case R.id.radioAccelerometer:
				mSensorManager.registerListener(this,
						mLinearAcceleration,
						10000);
				mStatusTextView.setText("receiving data from accelerometer");
				break;

			case R.id.radioGyroscope:
				mSensorManager.registerListener(this,
						mGyroscope,
						SensorManager.SENSOR_DELAY_FASTEST);
				mStatusTextView.setText("receiving data from gyroscope");
				break;
		}*/

		mSensorManager.registerListener(this,
				mLinearAcceleration,
				10000);
		//mStatusTextView.setText("receiving data from linear acceleration sensor");

		
		// We want this service to continue running until it is explicitly
		// stopped, so return sticky.
		return START_STICKY;
	}

	@Override
	public void onDestroy()
	{
		Toast.makeText(this, "Stopping receiving data", Toast.LENGTH_SHORT).show();
		
//		try
//		{
//			mOutputLinearAcceleration.close();
//		}
//		catch (IOException e)
//		{
//			e.printStackTrace();
//		}
		mSensorManager.unregisterListener(this);

		// Cancel the persistent notification.
		//mNM.cancel(R.string.remote_service_started);

		// Tell the user we stopped.
		Toast.makeText(this, R.string.remote_service_stopped, Toast.LENGTH_SHORT).show();

		wakeLock.release();

		try
		{
			mBinder.unregisterCallback(mSelfCallback);
		}
		catch (RemoteException e)
		{
			// There is nothing special we need to do if the service
			// has crashed.
		}

		// Unregister all callbacks.
		mCallbacks.kill();

		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent)
	{
		// Select the interface to return.  If your service only implements
		// a single interface, you can just return it here without checking
		// the Intent.
		if (IFallDetectionService.class.getName().equals(intent.getAction()))
		{
			return mBinder;
		}
		return null;
	}

	/**
	 * The IFallDetectionService is defined through IDL
	 */
	private final IFallDetectionService.Stub	mBinder	= new IFallDetectionService.Stub()
												{
													public void registerCallback(IFallDetectionServiceCallback cb)
													{
														if (cb != null)
															mCallbacks.register(cb);
													}

													public void unregisterCallback(IFallDetectionServiceCallback cb)
													{
														if (cb != null)
															mCallbacks.unregister(cb);
													}
												};

	@Override
	public void onTaskRemoved(Intent rootIntent)
	{
		Toast.makeText(this, "Task removed: " + rootIntent, Toast.LENGTH_LONG).show();
	}

	public void onAccuracyChanged(Sensor sensor, int accuracy)
	{
		switch (accuracy)
		{
			case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
				Toast.makeText(this, "maximum accuracy", Toast.LENGTH_LONG).show();
				break;
			case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
				Toast.makeText(this, "average level of accuracy", Toast.LENGTH_LONG).show();
				break;
			case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
				Toast.makeText(this, "low accuracy", Toast.LENGTH_LONG).show();
				break;
			case SensorManager.SENSOR_STATUS_UNRELIABLE:
				Toast.makeText(this, "sensor cannot be trusted", Toast.LENGTH_LONG).show();
				break;

		}
	}

	public void onSensorChanged(SensorEvent event)
	{

		synchronized (this)
		{

			switch (event.sensor.getType())
			{
				case Sensor.TYPE_LINEAR_ACCELERATION:

						final int N = mCallbacks.beginBroadcast();
						for (int i = 0; i < N; i++)
						{
							try
							{
								mCallbacks.getBroadcastItem(i).sensorChanged(Math.sqrt( event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2] ),
										event.timestamp);
							}
							catch (RemoteException e)
							{
								// The RemoteCallbackList will take care of removing
								// the dead object for us.
							}
						}
						mCallbacks.finishBroadcast();

					break;

			}
		}
	}

	private IFallDetectionServiceCallback	mSelfCallback	= new IFallDetectionServiceCallback.Stub()
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
//															synchronized (this)
//															{
//																boolean changed = false;
//																String stateVal = "";
//
//																svm_node[] svmInputs = new svm_node[SVM_INPUTS];
//																for (int i = 0; i < SVM_INPUTS; i++)
//																{
//																	svmInputs[i] = new svm_node();
//																	svmInputs[i].index = i + 1;
//																}
//
//																{
//																	double then = mElementsList.removeAt(0);
//																	mElementsList.add(resultant);
//																	
//																	double prevMean = mMean;
//																	
//																	mMean += (resultant - then) / (double) COUNTS;
//																	
//																	mAccVar += (resultant - prevMean) * ( resultant - mMean) - (then - prevMean) * (then - mMean);
//																	
//																	mVariance = mAccVar / (double) COUNTS;
//																	
//																	
//																	svmInputs[0].value = mMean;
//																	svmInputs[1].value = mVariance;
//
//																}
//
//																{
//																	for (int i = 0; i < SVM_INPUTS; i++)
//																		svmInputs[i].value =
//																				((svmInputs[i].value - mGlobalMinMaxRange[i][MIN]) / mGlobalMinMaxRange[i][RANGE]) * 2.0 - 1.0;
//
//																	//svm fall or adl
//																	SvmPredictionCombined = svm.svm_predict(mCombinedFallDetectionModel, svmInputs);
//																	switch ((int) SvmPredictionCombined)
//																	{
//																		case 1:
//																			stateVal = ACTIVITIES[0];
//																			break;
//																		case -1:
//																			stateVal = ACTIVITIES[1];
//																			break;
//																	}
//
//																	SvmPredictionClassified = svm.svm_predict(mClassifiedFallDetectionModel, svmInputs);
//																	if (SvmPredictionClassified > 0)
//																	{
//																		switch ((int) SvmPredictionClassified)
//																		{
//																			case 1:
//																				stateVal += ACTIVITIES_ADL_CLASSES[0];
//																				break;
//																			case 2:
//																				stateVal += ACTIVITIES_ADL_CLASSES[1];
//																				break;
//																			case 3:
//																				stateVal += ACTIVITIES_ADL_CLASSES[2];
//																				break;
//																			case 4:
//																				stateVal += ACTIVITIES_ADL_CLASSES[3];
//																				break;
//																			case 5:
//																				stateVal += ACTIVITIES_ADL_CLASSES[4];
//																				break;
//																			case 6:
//																				stateVal += ACTIVITIES_ADL_CLASSES[5];
//																				break;
//																			case 7:
//																				stateVal += ACTIVITIES_ADL_CLASSES[6];
//																				break;
//																			case 8:
//																				stateVal += ACTIVITIES_ADL_CLASSES[7];
//																				break;
//																		}
//																	}
//																	else
//																	{
//
//																		switch ((int) SvmPredictionClassified)
//																		{
//																			case -1:
//																				stateVal += ACTIVITIES_FALL_CLASSES[0];
//																				break;
//																			case -2:
//																				stateVal += ACTIVITIES_FALL_CLASSES[1];
//																				break;
//																			case -3:
//																				stateVal += ACTIVITIES_FALL_CLASSES[2];
//																				break;
//																			case -4:
//																				stateVal += ACTIVITIES_FALL_CLASSES[3];
//																				break;
//																			case -5:
//																				stateVal += ACTIVITIES_FALL_CLASSES[4];
//																				break;
//																			case -6:
//																				stateVal += ACTIVITIES_FALL_CLASSES[5];
//																				break;
//																			case -7:
//																				stateVal += ACTIVITIES_FALL_CLASSES[6];
//																				break;
//																		}
//																	}
//																	
//																	if ( SvmPredictionCombined != SvmPredictionCombinedPrev ||
//																		 SvmPredictionClassified != SvmPredictionClassifiedPrev)
//																	{
//																		changed = true;
//
//																		if ( SvmPredictionCombined < 0 && SvmPredictionClassified < 0)
//																		{
//																			if (mBeep)
//																			{
//																				mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP);
//																			
//																				/*mCurrentState.setText(stateVal);
//																				mLogEditText.append(stateVal + " " + getDate() + "\n");
//																				if (mTalkBack)
//																				{
//																					sensorActivity.mTts.speak(stateVal, TextToSpeech.QUEUE_FLUSH, null);
//																				}*/
//																			}
//																		}
//
//																	}
//																	SvmPredictionClassifiedPrev = SvmPredictionClassified;
//																	SvmPredictionCombinedPrev = SvmPredictionCombined;
//
//																	/*if (changed)
//																	{
//																		sensorActivity.mCurrentState.setText(stateVal);
//																		sensorActivity.mLogEditText.append(stateVal + "\n");
//																		if (sensorActivity.mTalkBack)
//																		{
//																			sensorActivity.mTts.speak(stateVal, TextToSpeech.QUEUE_FLUSH, null);
//																		}
//																	}*/
//																}
//																
//																mTimeStampsList.removeAt(0);
//																mTimeStampsList.add(timestamp);
//																//sensorActivity.mTimeStampsList.add(System.nanoTime());
//
//																/*if (mUpdateCounter++ % UPDATE_COUNTER == 0)
//																{
//																	
//																	mAverageTextView.setText( String.format("%10.5f", sensorActivity.mMean) );
//																	mVarianceTextView.setText( String.format("%10.5f", sensorActivity.mVariance) );
//																	mFrequencyTextView.setText(String.format("%10.5f Hz", COUNTS
//																			/ ((double) (sensorActivity.mTimeStampsList.get(COUNTS - 1) - sensorActivity.mTimeStampsList.get(0)) / 1000000000.0)));
//																}*/
//																
//															
//															
//																try
//																{
//																	mOutputLinearAcceleration.write(String.format("%f, %d", resultant, timestamp));
//																	mOutputLinearAcceleration.newLine();
//																}
//																catch (IOException e)
//																{
//																	e.printStackTrace();
//																}
//															}
														}
													};

													/*private String getDate() {
														SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
														return sdf.format(new Date());
													}*/
	/**
	 * Show a notification while this service is running.
	 */
	/*private void showNotification()
	{
		// In this sample, we'll use the same text for the ticker and the expanded notification
		CharSequence text = getText(R.string.remote_service_started);

		// The PendingIntent to launch our activity if the user selects this notification
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
				new Intent(this, MainActivity.class), 0);

		// Set the info for the views that show in the notification panel.
		Notification notification = new NotificationCompat.Builder((Context) this)
				.setContentText(getText(R.string.remote_service_label))
				.setSubText(text)
				.setSmallIcon(R.drawable.ic_launcher)
				.setContentIntent(contentIntent)
				.build();

		// Send the notification.
		// We use a string id because it is a unique number.  We use it later to cancel.
		mNM.notify(R.string.remote_service_started, notification);
	}*/

	// ----------------------------------------------------------------------

	private void acquireWakeLock()
	{
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

		wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, FallDetectionService.class.getName());
		wakeLock.acquire();
	}
}
