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
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import libsvm.svm;
import libsvm.svm_model;
import libsvm.svm_node;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;

public class FallDetectionService extends Service implements SensorEventListener
{
	//constants
	private static final String										TAG							= "FallDetectionService";
	private static final int										NOTIFICATION_ID				= 101;
	private static final int										REQUEST_CODE				= 1;

	//fall detection service
	private final RemoteCallbackList<IFallDetectionServiceCallback>	mCallbacks					= new RemoteCallbackList<IFallDetectionServiceCallback>();

	//sensor
	private SensorManager											mSensorManager;
	private Sensor													mLinearAcceleration;
	private SensorDataHandler										mSensorHandler;

	private PowerManager.WakeLock									mWakeLock;

	private BufferedWriter											mOutputLinearAcceleration;

	//svm stuff

	//constants and magic numbers
	private static final int										COUNTS						= 234;
	private static final int										MIN							= 0;
	private static final int										MAX							= 1;
	private static final int										RANGE						= 2;

	private static final int										SVM_INPUTS					= 2;

	private static final int										UPDATE_COUNTER				= 10;

	private static final String										ACTIVITIES[]				= { "ADL ", "Fall " };
	private static final String										ACTIVITIES_ADL_CLASSES[]	= { "Normal walk", "Standing quietly", "Standing to sitting",
			"Standing to lying",
			"Sit to stand", "Reach and pick", "Ascend stairs", "Descend stairs"				};
	private static final String										ACTIVITIES_FALL_CLASSES[]	= { "Bump", "Misstep", "Incorrect stand to sit",
			"Incorrect sit to stand", "Collapse", "Slip",
			"Trip"																				};

	//TalkBack
	private TextToSpeech											mTts;

	//flags
	private boolean													mTalkBack;
	private boolean													mBeep;
	private boolean													mLogSensorData;
	private boolean													mShowData;
	private boolean													mFallsLog;

	private ToneGenerator											mToneGenerator;

	//lists
	private TDoubleList												mElementsList;

	private double													mMean;
	private double													mAccVar;
	private double													mVariance;
	private TLongList												mTimeStampsList;

	//this variable is to reduce frequency of the screen updates - we don't need it to update text field values so often
	private int														mUpdateCounter;

	//svm
	private svm_model												mCombinedFallDetectionModel;
	private svm_model												mClassifiedFallDetectionModel;
	private double													mSvmPredictionCombined, mSvmPredictionClassified;
	private double													mSvmPredictionCombinedPrev, mSvmPredictionClassifiedPrev;

	private double													mGlobalMinMaxRange[][]		= new double[SVM_INPUTS][3];
	
	public void onCreate()
	{
		super.onCreate();

		loadPreferences();

		acquireWakeLock();

		mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		mLinearAcceleration = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
		mSensorHandler = new SensorDataHandler(this);

		if(mBeep)
		{
			mToneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
		}

		initSvmVariables();
	}

	public int onStartCommand(Intent intent, int flags, int startId)
	{
		Log.i(TAG, "Received start id " + startId + ": " + intent);

		startForeground(NOTIFICATION_ID, getCompatNotification());

		mSensorManager.registerListener(this, mLinearAcceleration, 10000);

		if(mLogSensorData)
		{
			initSensorLog();
		}

		mElementsList.fill(0, COUNTS, 0);

		mTimeStampsList.fill(0, COUNTS, 0);

		mMean = 0.0;
		mAccVar = 0.0;
		mVariance = 0.0;

		// We want this service to continue running until it is explicitly stopped, so start sticky.
		return START_STICKY;
	}

	@Override
	public void onDestroy()
	{
		closeSensorLog();

		mSensorManager.unregisterListener(this);

		mWakeLock.release();

//		try
//		{
//			mBinder.unregisterCallback(mSelfCallback);
//		}
//		catch (RemoteException e)
//		{
//			e.printStackTrace();
//		}

		// Unregister all callbacks.
		mCallbacks.kill();
		
		Log.d(TAG, "Remote service has stopped");

		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent)
	{
		// returning Binding interface
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
		Log.d(TAG, "Task removed: " + rootIntent);
	}

	public void onAccuracyChanged(Sensor sensor, int accuracy)
	{
		switch (accuracy)
		{
			case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
				Log.d(TAG, "linear acceleration maximum accuracy");
				break;
			case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
				Log.d(TAG, "linear acceleration average level of accuracy");
				break;
			case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
				Log.d(TAG, "linear acceleration low accuracy");
				break;
			case SensorManager.SENSOR_STATUS_UNRELIABLE:
				Log.d(TAG, "linear acceleration sensor accuracy cannot be trusted");
				break;
		}
	}

	public void onSensorChanged(SensorEvent event)
	{
		synchronized (this)
		{
			Message mSensorMessage = new Message();
			Bundle mMessageBundle = new Bundle();

			if(mLogSensorData)
			{
				try
				{
					mOutputLinearAcceleration.write(String.format("%f, %f, %f, %d", event.values[0], event.values[1], event.values[2], event.timestamp));
					mOutputLinearAcceleration.newLine();
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}
			}

			mMessageBundle.putDouble("Resultant", Math.sqrt( event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2] ));
			mMessageBundle.putLong("Timestamp", event.timestamp);

			mSensorMessage.setData(mMessageBundle);

			mSensorHandler.sendMessage(mSensorMessage);
		}
	}

	static class SensorDataHandler extends Handler
	{
		WeakReference<FallDetectionService>	mFallDetectionService;

		SensorDataHandler(FallDetectionService fallDetectionService)
		{
			mFallDetectionService = new WeakReference<FallDetectionService>(fallDetectionService);
		}

		@Override
		public void handleMessage(Message msg)
		{
			synchronized (this)
			{
				FallDetectionService fallDetectionService = mFallDetectionService.get();

				Bundle bundle = msg.getData();
				double resultant = bundle.getDouble("Resultant");

				String stateVal = "";

				svm_node[] svmInputs = new svm_node[SVM_INPUTS];
				for (int i = 0; i < SVM_INPUTS; i++)
				{
					svmInputs[i] = new svm_node();
					svmInputs[i].index = i + 1;
				}

				{
					double then = fallDetectionService.mElementsList.removeAt(0);
					fallDetectionService.mElementsList.add(resultant);
					
					double prevMean = fallDetectionService.mMean;
					
					fallDetectionService.mMean += (resultant - then) / (double) COUNTS;
					
					fallDetectionService.mAccVar += (resultant - prevMean) * ( resultant - fallDetectionService.mMean) - (then - prevMean) * (then - fallDetectionService.mMean);
					
					fallDetectionService.mVariance = fallDetectionService.mAccVar / (double) COUNTS;

					svmInputs[0].value = fallDetectionService.mMean;
					svmInputs[1].value = fallDetectionService.mVariance;

				}

				{
					for (int i = 0; i < SVM_INPUTS; i++)
						svmInputs[i].value =
								((svmInputs[i].value - fallDetectionService.mGlobalMinMaxRange[i][MIN]) / fallDetectionService.mGlobalMinMaxRange[i][RANGE]) * 2.0 - 1.0;

					//svm fall or adl
					fallDetectionService.mSvmPredictionCombined = svm.svm_predict(fallDetectionService.mCombinedFallDetectionModel, svmInputs);
					switch ((int) fallDetectionService.mSvmPredictionCombined)
					{
						case 1:
							stateVal = ACTIVITIES[0];
							break;
						case -1:
							stateVal = ACTIVITIES[1];
							break;
					}

					fallDetectionService.mSvmPredictionClassified = svm.svm_predict(fallDetectionService.mClassifiedFallDetectionModel, svmInputs);

					switch ((int) fallDetectionService.mSvmPredictionClassified)
					{
						case 1:
							stateVal += ACTIVITIES_ADL_CLASSES[0];
							break;
						case 2:
							stateVal += ACTIVITIES_ADL_CLASSES[1];
							break;
						case 3:
							stateVal += ACTIVITIES_ADL_CLASSES[2];
							break;
						case 4:
							stateVal += ACTIVITIES_ADL_CLASSES[3];
							break;
						case 5:
							stateVal += ACTIVITIES_ADL_CLASSES[4];
							break;
						case 6:
							stateVal += ACTIVITIES_ADL_CLASSES[5];
							break;
						case 7:
							stateVal += ACTIVITIES_ADL_CLASSES[6];
							break;
						case 8:
							stateVal += ACTIVITIES_ADL_CLASSES[7];
							break;
						case -1:
							stateVal += ACTIVITIES_FALL_CLASSES[0];
							break;
						case -2:
							stateVal += ACTIVITIES_FALL_CLASSES[1];
							break;
						case -3:
							stateVal += ACTIVITIES_FALL_CLASSES[2];
							break;
						case -4:
							stateVal += ACTIVITIES_FALL_CLASSES[3];
							break;
						case -5:
							stateVal += ACTIVITIES_FALL_CLASSES[4];
							break;
						case -6:
							stateVal += ACTIVITIES_FALL_CLASSES[5];
							break;
						case -7:
							stateVal += ACTIVITIES_FALL_CLASSES[6];
							break;
					}
					
					if ( fallDetectionService.mSvmPredictionCombined != fallDetectionService.mSvmPredictionCombinedPrev ||
							fallDetectionService.mSvmPredictionClassified != fallDetectionService.mSvmPredictionClassifiedPrev)
					{
						if ( fallDetectionService.mSvmPredictionCombined < 0 && fallDetectionService.mSvmPredictionClassified < 0)
						{
							if (fallDetectionService.mBeep)
							{
								fallDetectionService.mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP);
							
								if(fallDetectionService.mFallsLog)
								{
									final int N = fallDetectionService.mCallbacks.beginBroadcast();
									for (int i = 0; i < N; i++) 
									{
										try
										{
											fallDetectionService.mCallbacks.getBroadcastItem(i).fallMessage(stateVal);
										}
										catch (RemoteException e)
										{
											e.printStackTrace();
										}
									}
									fallDetectionService.mCallbacks.finishBroadcast();
								}

								if (fallDetectionService.mTalkBack)
								{
									fallDetectionService.mTts.speak(stateVal, TextToSpeech.QUEUE_FLUSH, null);
								}
							}
						}

					}
					fallDetectionService.mSvmPredictionClassifiedPrev = fallDetectionService.mSvmPredictionClassified;
					fallDetectionService.mSvmPredictionCombinedPrev = fallDetectionService.mSvmPredictionCombined;
				}
				
				fallDetectionService.mTimeStampsList.removeAt(0);
				fallDetectionService.mTimeStampsList.add(bundle.getLong("Timestamp"));

				if (fallDetectionService.mUpdateCounter++ % UPDATE_COUNTER == 0 && fallDetectionService.mShowData)
				{
					final int N = fallDetectionService.mCallbacks.beginBroadcast();
					for (int i = 0; i < N; i++) 
					{
						try
						{
							fallDetectionService.mCallbacks.getBroadcastItem(i).updateStats(fallDetectionService.mMean,
									fallDetectionService.mVariance,
									COUNTS/ ((double) (fallDetectionService.mTimeStampsList.get(COUNTS - 1) - fallDetectionService.mTimeStampsList.get(0)) / 1000000000.0) );
						}
						catch (RemoteException e)
						{
							e.printStackTrace();
						}
					}
					fallDetectionService.mCallbacks.finishBroadcast();
				}
				
			
			}

		}
	}

//	private IFallDetectionServiceCallback	mSelfCallback	= new IFallDetectionServiceCallback.Stub()
//													{
//														/**
//														 * This is called by the remote service regularly to tell us about
//														 * new values.  Note that IPC calls are dispatched through a thread
//														 * pool running in each process, so the code executing here will
//														 * NOT be running in our main thread like most other things -- so,
//														 * to update the UI, we need to use a Handler to hop over there.
//														 */
//														public void updateStats(double average, double variance, double frequency)
//														{
////															synchronized (this)
////															{
////																boolean changed = false;
////																String stateVal = "";
////
////																svm_node[] svmInputs = new svm_node[SVM_INPUTS];
////																for (int i = 0; i < SVM_INPUTS; i++)
////																{
////																	svmInputs[i] = new svm_node();
////																	svmInputs[i].index = i + 1;
////																}
////
////																{
////																	double then = mElementsList.removeAt(0);
////																	mElementsList.add(resultant);
////																	
////																	double prevMean = mMean;
////																	
////																	mMean += (resultant - then) / (double) COUNTS;
////																	
////																	mAccVar += (resultant - prevMean) * ( resultant - mMean) - (then - prevMean) * (then - mMean);
////																	
////																	mVariance = mAccVar / (double) COUNTS;
////																	
////																	
////																	svmInputs[0].value = mMean;
////																	svmInputs[1].value = mVariance;
////
////																}
////
////																{
////																	for (int i = 0; i < SVM_INPUTS; i++)
////																		svmInputs[i].value =
////																				((svmInputs[i].value - mGlobalMinMaxRange[i][MIN]) / mGlobalMinMaxRange[i][RANGE]) * 2.0 - 1.0;
////
////																	//svm fall or adl
////																	SvmPredictionCombined = svm.svm_predict(mCombinedFallDetectionModel, svmInputs);
////																	switch ((int) SvmPredictionCombined)
////																	{
////																		case 1:
////																			stateVal = ACTIVITIES[0];
////																			break;
////																		case -1:
////																			stateVal = ACTIVITIES[1];
////																			break;
////																	}
////
////																	SvmPredictionClassified = svm.svm_predict(mClassifiedFallDetectionModel, svmInputs);
////																	if (SvmPredictionClassified > 0)
////																	{
////																		switch ((int) SvmPredictionClassified)
////																		{
////																			case 1:
////																				stateVal += ACTIVITIES_ADL_CLASSES[0];
////																				break;
////																			case 2:
////																				stateVal += ACTIVITIES_ADL_CLASSES[1];
////																				break;
////																			case 3:
////																				stateVal += ACTIVITIES_ADL_CLASSES[2];
////																				break;
////																			case 4:
////																				stateVal += ACTIVITIES_ADL_CLASSES[3];
////																				break;
////																			case 5:
////																				stateVal += ACTIVITIES_ADL_CLASSES[4];
////																				break;
////																			case 6:
////																				stateVal += ACTIVITIES_ADL_CLASSES[5];
////																				break;
////																			case 7:
////																				stateVal += ACTIVITIES_ADL_CLASSES[6];
////																				break;
////																			case 8:
////																				stateVal += ACTIVITIES_ADL_CLASSES[7];
////																				break;
////																		}
////																	}
////																	else
////																	{
////
////																		switch ((int) SvmPredictionClassified)
////																		{
////																			case -1:
////																				stateVal += ACTIVITIES_FALL_CLASSES[0];
////																				break;
////																			case -2:
////																				stateVal += ACTIVITIES_FALL_CLASSES[1];
////																				break;
////																			case -3:
////																				stateVal += ACTIVITIES_FALL_CLASSES[2];
////																				break;
////																			case -4:
////																				stateVal += ACTIVITIES_FALL_CLASSES[3];
////																				break;
////																			case -5:
////																				stateVal += ACTIVITIES_FALL_CLASSES[4];
////																				break;
////																			case -6:
////																				stateVal += ACTIVITIES_FALL_CLASSES[5];
////																				break;
////																			case -7:
////																				stateVal += ACTIVITIES_FALL_CLASSES[6];
////																				break;
////																		}
////																	}
////																	
////																	if ( SvmPredictionCombined != SvmPredictionCombinedPrev ||
////																		 SvmPredictionClassified != SvmPredictionClassifiedPrev)
////																	{
////																		changed = true;
////
////																		if ( SvmPredictionCombined < 0 && SvmPredictionClassified < 0)
////																		{
////																			if (mBeep)
////																			{
////																				mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP);
////																			
////																				/*mCurrentState.setText(stateVal);
////																				mLogEditText.append(stateVal + " " + getDate() + "\n");
////																				if (mTalkBack)
////																				{
////																					sensorActivity.mTts.speak(stateVal, TextToSpeech.QUEUE_FLUSH, null);
////																				}*/
////																			}
////																		}
////
////																	}
////																	SvmPredictionClassifiedPrev = SvmPredictionClassified;
////																	SvmPredictionCombinedPrev = SvmPredictionCombined;
////
////																	/*if (changed)
////																	{
////																		sensorActivity.mCurrentState.setText(stateVal);
////																		sensorActivity.mLogEditText.append(stateVal + "\n");
////																		if (sensorActivity.mTalkBack)
////																		{
////																			sensorActivity.mTts.speak(stateVal, TextToSpeech.QUEUE_FLUSH, null);
////																		}
////																	}*/
////																}
////																
////																mTimeStampsList.removeAt(0);
////																mTimeStampsList.add(timestamp);
////																//sensorActivity.mTimeStampsList.add(System.nanoTime());
////
////																/*if (mUpdateCounter++ % UPDATE_COUNTER == 0)
////																{
////																	
////																	mAverageTextView.setText( String.format("%10.5f", sensorActivity.mMean) );
////																	mVarianceTextView.setText( String.format("%10.5f", sensorActivity.mVariance) );
////																	mFrequencyTextView.setText(String.format("%10.5f Hz", COUNTS
////																			/ ((double) (sensorActivity.mTimeStampsList.get(COUNTS - 1) - sensorActivity.mTimeStampsList.get(0)) / 1000000000.0)));
////																}*/
////																
////															
////															
////																try
////																{
////																	mOutputLinearAcceleration.write(String.format("%f, %d", resultant, timestamp));
////																	mOutputLinearAcceleration.newLine();
////																}
////																catch (IOException e)
////																{
////																	e.printStackTrace();
////																}
////															}
//														}
//													};

													/*private String getDate() {
														SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
														return sdf.format(new Date());
													}*/

	/* Utility functions */

	private void loadPreferences()
	{
		SharedPreferences mySharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

		mTalkBack = mySharedPreferences.getBoolean("talkback", false);
		mBeep = mySharedPreferences.getBoolean("beep", true);
		mLogSensorData = mySharedPreferences.getBoolean("logsensor", false);
		mShowData = mySharedPreferences.getBoolean("showdata", true);
		mFallsLog = mySharedPreferences.getBoolean("fallslog", true);
	}

	private void acquireWakeLock()
	{
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

		mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, FallDetectionService.class.getName());
		mWakeLock.acquire();
	}

	private void initSvmVariables()
	{
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
			e.printStackTrace();
		}

		mGlobalMinMaxRange[0][MIN] = 0;
		mGlobalMinMaxRange[1][MIN] = 0;

		mGlobalMinMaxRange[0][MAX] = 18.07792331;
		mGlobalMinMaxRange[1][MAX] = 166.5527461;

		for (int i = 0; i < SVM_INPUTS; i++)
			mGlobalMinMaxRange[i][RANGE] = mGlobalMinMaxRange[i][MAX] - mGlobalMinMaxRange[i][MIN];
	}

	/** build the notification which includes a pending intent */
	private Notification getCompatNotification()
	{
		Notification.Builder builder = new Notification.Builder(this);
		builder.setSmallIcon(R.drawable.ic_launcher)
				.setContentTitle("Fall Detection service is running")
				.setTicker("Fall Detection service is started")
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

	private void initSensorLog()
	{
		Date lm = new Date();
		
		mOutputLinearAcceleration = null;
		String fileName = "FallDetectionLogger_linear_acceleration" + new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(lm) + ".csv";
		try
		{
			File configFile = new File(Environment.getExternalStorageDirectory().getPath(), fileName);
			FileWriter fileWriter = new FileWriter(configFile);
			mOutputLinearAcceleration = new BufferedWriter(fileWriter);
		}
		catch (IOException ex)
		{
			Log.e(FallDetectionService.class.getName(), ex.toString());
		}

		try
		{
			mOutputLinearAcceleration.write("X, Y, Z, Timestamp, ");
			mOutputLinearAcceleration.newLine();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}
	
	private void closeSensorLog()
	{
		if(mOutputLinearAcceleration != null)
		{
			try
			{
				mOutputLinearAcceleration.close();
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
		}
	}
}
