package com.ccrstech.openandroiddex.launcher;

/**
 * Where what the phone's touchpad does lands ({@link Touchpad}): the desktop a computer
 * shows, through the window daemon ({@link DesktopPointer}), or the computer's own windows
 * of the phone's apps, through the phone's app server ({@link HostPointer}). The pad says
 * what the fingers meant; this moves, presses and scrolls.
 */
interface PadTarget {

    /** The pad is in front: the pointer appears, and what is under it learns it is hovered. */
    void show();

    /** The pad is closing for good: anything held is let go, and the connection closed. */
    void release();

    /** The keyboard back to what the pad points at, if the phone's own screen took it. */
    void focusDesktop();

    /** Whether what this sends to has been taking it. */
    boolean answering();

    /** The display pointed at changed size or density. */
    void displayChanged();

    /** A pointer setting changed in Settings: the arrow drawn again, where there is one. */
    void restyle();

    /** The pad's speed setting, read again. */
    void readSpeed();

    /** The finger moved {@code dxDp}, {@code dyDp} of the phone's dp, at {@code speedDpPerMs}. */
    void moveBy(float dxDp, float dyDp, float speedDpPerMs);

    /** Hold exactly these MotionEvent.BUTTON_* down where the pointer is; 0 lets go. */
    void press(int buttons);

    /** One click of {@code button} where the pointer is. */
    void click(int button);

    /** The wheel, in notches: up and right are positive. */
    void scroll(float h, float v);

    /** One key, pressed and let go. */
    void key(int code);
}
