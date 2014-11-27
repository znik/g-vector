/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Original file: C:\\Users\\evgeny\\git\\g-vector\\FallDetection2\\src\\ca\\jvsh\\fall\\IFallDetectionServiceCallback.aidl
 */
package ca.jvsh.fall;
/**
 * Example of a callback interface used by IRemoteService to send
 * synchronous notifications back to its clients.  Note that this is a
 * one-way interface so the server does not block waiting for the client.
 */
public interface IFallDetectionServiceCallback extends android.os.IInterface
{
/** Local-side IPC implementation stub class. */
public static abstract class Stub extends android.os.Binder implements ca.jvsh.fall.IFallDetectionServiceCallback
{
private static final java.lang.String DESCRIPTOR = "ca.jvsh.fall.IFallDetectionServiceCallback";
/** Construct the stub at attach it to the interface. */
public Stub()
{
this.attachInterface(this, DESCRIPTOR);
}
/**
 * Cast an IBinder object into an ca.jvsh.fall.IFallDetectionServiceCallback interface,
 * generating a proxy if needed.
 */
public static ca.jvsh.fall.IFallDetectionServiceCallback asInterface(android.os.IBinder obj)
{
if ((obj==null)) {
return null;
}
android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
if (((iin!=null)&&(iin instanceof ca.jvsh.fall.IFallDetectionServiceCallback))) {
return ((ca.jvsh.fall.IFallDetectionServiceCallback)iin);
}
return new ca.jvsh.fall.IFallDetectionServiceCallback.Stub.Proxy(obj);
}
@Override public android.os.IBinder asBinder()
{
return this;
}
@Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
{
switch (code)
{
case INTERFACE_TRANSACTION:
{
reply.writeString(DESCRIPTOR);
return true;
}
case TRANSACTION_updateStats:
{
data.enforceInterface(DESCRIPTOR);
double _arg0;
_arg0 = data.readDouble();
double _arg1;
_arg1 = data.readDouble();
double _arg2;
_arg2 = data.readDouble();
this.updateStats(_arg0, _arg1, _arg2);
return true;
}
case TRANSACTION_fallMessage:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _arg0;
_arg0 = data.readString();
this.fallMessage(_arg0);
return true;
}
}
return super.onTransact(code, data, reply, flags);
}
private static class Proxy implements ca.jvsh.fall.IFallDetectionServiceCallback
{
private android.os.IBinder mRemote;
Proxy(android.os.IBinder remote)
{
mRemote = remote;
}
@Override public android.os.IBinder asBinder()
{
return mRemote;
}
public java.lang.String getInterfaceDescriptor()
{
return DESCRIPTOR;
}
/**
     * Called when the service has a new value for you.
     */
@Override public void updateStats(double average, double variance, double frequency) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeDouble(average);
_data.writeDouble(variance);
_data.writeDouble(frequency);
mRemote.transact(Stub.TRANSACTION_updateStats, _data, null, android.os.IBinder.FLAG_ONEWAY);
}
finally {
_data.recycle();
}
}
/**
     * Called when fall is detected.
     */
@Override public void fallMessage(java.lang.String fallMsg) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeString(fallMsg);
mRemote.transact(Stub.TRANSACTION_fallMessage, _data, null, android.os.IBinder.FLAG_ONEWAY);
}
finally {
_data.recycle();
}
}
}
static final int TRANSACTION_updateStats = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
static final int TRANSACTION_fallMessage = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
}
/**
     * Called when the service has a new value for you.
     */
public void updateStats(double average, double variance, double frequency) throws android.os.RemoteException;
/**
     * Called when fall is detected.
     */
public void fallMessage(java.lang.String fallMsg) throws android.os.RemoteException;
}
