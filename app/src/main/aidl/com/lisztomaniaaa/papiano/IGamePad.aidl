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

    // Synchronous on purpose: bounded backpressure prevents Binder oneway queue
    // buildup during very dense MIDI playback (e.g. Etude Op.10 No.4).
    void qwertyKey(int key, boolean isDown, int velocity);

    // Packed triples: note, down(1/0), velocity. Used by MIDI file playback
    // to deliver same-tick chords/bursts in one Binder transaction.
    void qwertyBatch(in int[] noteEvents);
    void pianoRoomsKey(in int[] noteIntArray);
}