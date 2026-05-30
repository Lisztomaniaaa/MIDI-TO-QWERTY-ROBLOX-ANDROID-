package com.lisztomaniaaa.papiano;

interface IGamePad {

    void changeMode(int mode);

    int getCurrentMode();

//    void inputEvent(float xValue,float yValue);

//    void pressTL(boolean pressed);

//    void pressTR(boolean pressed);

//    void pressThumbL(boolean pressed);

    boolean create();

    boolean close();

    void closeAndExit();

//    void syncPrefs(boolean invX,boolean invY,int sensityX,int sensityY);

    // oneway: fire-and-forget async transaction. Caller (note hot-path) doesn't
    // block waiting for the daemon round-trip -> lower per-note latency. Oneway
    // calls to the same binder are delivered in order, so note on/off ordering
    // is preserved. Dead-binder still throws at transact time (caught upstream).
    oneway void qwertyKey(int key, boolean isDown, int velocity);
    void pianoRoomsKey(in int[] noteIntArray);
}