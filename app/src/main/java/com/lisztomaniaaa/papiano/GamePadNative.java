package com.lisztomaniaaa.papiano;

import static com.lisztomaniaaa.papiano.MainActivity.TAG;

import android.app.IApplicationThread;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.IRotationWatcher;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

public class GamePadNative {
    static boolean isUInputCreated = false, isUHidCreated = false, isInputManagerCreated = false;
    static boolean invertAll = false, invertX = false, invertY = false;
    static float sensitivityXMode0, sensitivityYMode0, sensitivityXMode1, sensitivityYMode1, sensitivityXMode2, sensitivityYMode2;
    static float lastX = 0, lastY = 0;
    static int currentMode = 0;
    static android.hardware.input.InputManager im;
    static Method injectInputEventMethod;
    static MotionEvent.PointerProperties[] properties;
    static MotionEvent.PointerCoords[] pointerCoords;


    public static void main(String[] args) {
        //检查权限
        int uid = android.os.Process.myUid();
        if (uid != 0 && uid != 2000) {
            System.err.printf("Insufficient permission! Need to be launched by adb (uid 2000) or root (uid 0), but your uid is %d \n", uid);
            System.exit(255);
            return;
        }

        System.loadLibrary("tuoluoyi");

        System.out.println("Start GamePad Service. Enter \"exit\" here at any time to exit.");
        sendBinderToAppByStickyBroadcast();//发送binder给APP // This is called ONLY once, it's a special type of broadcast called a sticky broadcast
        // This tells other applications that this script has started
        // Due to the type of broadcast, applications which weren't listening when the broadcast was sent initially will still receive
        // this broadcast as soon as they start listening and hence it's called a sticky broadcast


        //加入JVM异常关闭时的处理程序
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                if (isUHidCreated) isUHidCreated = !nativeCloseUHid();
            }
        });

        watchDeviceRotation(); //监测设备是否位于逆向横屏状态，如果是逆向横屏则将陀螺仪数据乘以-1

        try {
            Scanner scanner = new Scanner(System.in);
            //用来保持进程不退出，同时如果用户输入exit则程序退出
            String inline;
            while ((inline = scanner.nextLine()) != null) {
                if (inline.equals("exit"))
                    break;
            }
            scanner.close();
        } catch (Exception unused) {
            //用户使用nohup命令启动，scanner捕捉不到任何输入,会抛出异常。
            // Block thread tanpa burn CPU. Object.wait() lebih ringan dari
            // Thread.sleep(MAX) karena gak perlu timer interrupt.
            try {
                final Object keepAlive = new Object();
                synchronized (keepAlive) {
                    keepAlive.wait(); // sleeps forever, 0% CPU
                }
            } catch (InterruptedException e) {
                // exit gracefully if interrupted
            }
        }


        if (isUHidCreated) isUHidCreated = !nativeCloseUHid();
        System.out.println("Stop GamePad Service.\n");
    }

    private static boolean getInputManager() {
        try {
            Method getInstanceMethod = android.hardware.input.InputManager.class.getDeclaredMethod("getInstance");
            im = (android.hardware.input.InputManager) getInstanceMethod.invoke(null);
            if (im == null) {
                System.err.println("Unable to get inputManager for mode2.");
                return false;
            }
            injectInputEventMethod = im.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
            injectInputEventMethod.setAccessible(true);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            System.err.println("Unable to get inputManager for mode2.");
            return false;
        }
        properties = new MotionEvent.PointerProperties[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        pointerCoords = new MotionEvent.PointerCoords[1];
        pointerCoords[0] = new MotionEvent.PointerCoords();
        pointerCoords[0].clear();
        return true;
    }


    private static void watchDeviceRotation() {

        //注册旋转观测器
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "window");
            Class<?> windowManagerStub = Class.forName("android.view.IWindowManager$Stub");
            Method asInterface = windowManagerStub.getMethod("asInterface", IBinder.class);
            IInterface windowManager = (IInterface) asInterface.invoke(null, binder);
            if (windowManager == null) {
                System.err.println("Unable to watch rotation. Skip it.");
                return;
            }
            Class<?> cls = windowManager.getClass();
            try {
                invertAll = (int) cls.getMethod("getDefaultDisplayRotation").invoke(windowManager) == 3;
            } catch (NoSuchMethodException unused) {
                invertAll = (int) cls.getMethod("getRotation").invoke(windowManager) == 3;
            }
            //新建旋转观测器
            IRotationWatcher rotationWatcher = new IRotationWatcher.Stub() {
                @Override
                public void onRotationChanged(int rotation) {
                    invertAll = rotation == 3;
                }
            };
            try {
                cls.getMethod("watchRotation", IRotationWatcher.class, int.class).invoke(windowManager, rotationWatcher, 0);
            } catch (NoSuchMethodException e) {
                cls.getMethod("watchRotation", IRotationWatcher.class).invoke(windowManager, rotationWatcher);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }


    static native boolean nativeCreateUHid();

    static native boolean nativeCloseUHid();


    static native void nativeQwertyKey(int noteNumber, boolean isDown, int velocity);

    static native void nativePianoRoomsKey(int[] noteIntArray);

    private static void sendBinderToAppByStickyBroadcast() {

        try {
            //生成binder
            IBinder binder = new IGamePad.Stub() {
                @Override
                public void qwertyKey(int noteNumber, boolean isDown, int velocity) throws RemoteException {
//                    int status = 500;
                    // Do something!

                    // QWERTY mode supports proper hold/sustain:
                    //   isDown=true  -> add note to held set, emit aggregated HID report
                    //   isDown=false -> remove note from held set, emit updated report
                    // Limit 6KRO (HID boot-protocol). Lihat native-lib.cpp utk detail.
                    //
                    // velocity: 0 = velocity OFF (QWERTY polos). >0 = Visual Piano
                    // velocity mode -> native akan tap Alt+<velKey> (skema 32-step)
                    // sebelum not kalau level velocity berubah. Gating ON/OFF
                    // dilakukan di tuoluoyiService (kirim 0 saat toggle OFF).
                    nativeQwertyKey(noteNumber, isDown, velocity);

//                    if (isDown) {
//                        //Log.d(MainActivity.TAG, "Key: " + Hid.keyboardCode[2])
//                        status=nativePianoKey(noteNumber, isDown);
//                    } else {
//                        status=nativePianoKey(noteNumber, isDown);
//                    }



//                    if (status == 0) {
//                        return "Acknowledged : " + noteNumber + " " + isDown;
//                    } else {
//                        return "Failure";
//                    }
                }

                @Override
                public void pianoRoomsKey(int[] noteIntArray) throws RemoteException {
//                    List<Integer> noteArrayList = new ArrayList<>();
//                    for (int note : noteIntList) {
//                        noteArrayList.add(note);
//                    }
//                    Log.d("A:", noteArrayList.toString());
                    Log.d("B:", Arrays.toString(noteIntArray));

                    nativePianoRoomsKey(noteIntArray);
                }

                @Override
                public void changeMode(int mode) throws RemoteException {
                    currentMode = mode;
                }

                @Override
                public int getCurrentMode() throws RemoteException {
                    return currentMode;
                }

                @Override
                public boolean close() throws RemoteException {
                    if (isUHidCreated) isUHidCreated = !nativeCloseUHid();
                    return !(isUHidCreated);
                }

                @Override
                // The following function creates an HID device only if it hasn't been created before
                // In case it has been created before, it just returns the boolean indicating it has already
                public boolean create() throws RemoteException {
                    Log.d(TAG, "GamePadNative has started successfully.");
                    if (!isUHidCreated)
                        isUHidCreated = nativeCreateUHid();
                    return isUHidCreated;
                }


                @Override
                public void closeAndExit() throws RemoteException {
                    if (isUHidCreated) isUHidCreated = !nativeCloseUHid();
                    System.out.println("Stop GamePad Service.\n");
                    System.exit(0);
                }
            };

            // Create an HID as soon as we start GamePadNative
            isUHidCreated = nativeCreateUHid();

            //把binder填到一个可以用Intent来传递的容器中
            BinderContainer binderContainer = new BinderContainer(binder);
            // 创建 Intent 对象，并将binder作为附加参数
            Intent intent = new Intent("intent.tuoluoyi.sendBinder");
            intent.putExtra("binder", binderContainer);

            Object iActivityManagerObj; // 获取 IActivityManager 类
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                iActivityManagerObj = Class.forName("android.app.IActivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class).invoke(null, "activity"));
            } else {
                Class<?> activityManagerNativeClass = Class.forName("android.app.ActivityManagerNative");
                Method getDefaultMethod = activityManagerNativeClass.getMethod("getDefault");
                iActivityManagerObj = getDefaultMethod.invoke(activityManagerNativeClass);
            }
            // 获取 broadcastIntent 方法
            Method broadcastIntentMethod = Class.forName("android.app.IActivityManager").getDeclaredMethod(
                    "broadcastIntent",
                    IApplicationThread.class,
                    Intent.class,
                    String.class,
                    IIntentReceiver.class,
                    int.class,
                    String.class,
                    Bundle.class,
                    String[].class,
                    int.class,
                    Bundle.class,
                    boolean.class,
                    boolean.class,
                    int.class
            );
            // 调用 broadcastIntent 方法，发送粘性广播
            broadcastIntentMethod.invoke(
                    iActivityManagerObj,
                    null,
                    intent,
                    null,
                    null,
                    -1,
                    null,
                    null,
                    null,
                    0,
                    null,
                    false,
                    true, // Makes broadcast sticky(meaning even if no components were listening at the time, they will receive all what they missed upon listening)
                    -1
            );

        } catch (Exception e) {
            System.err.println("Failed to send broadcast!");
            System.exit(-1);
        }
    }
}
