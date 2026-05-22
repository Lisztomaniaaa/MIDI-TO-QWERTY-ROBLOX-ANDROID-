package com.tile.tuoluoyi;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * LEGACY COMPAT CLASS — JANGAN DIHAPUS.
 *
 * Daemon papiano yg di-spawn Shizuku/root dari install LAMA (versi sebelum
 * package di-rename ke com.lisztomaniaaa.papiano) jalan di shell uid 2000
 * sebagai process terpisah. Process itu GAK MATI walaupun APK lama udah
 * di-uninstall — masih hidup, loop broadcast 'intent.tuoluoyi.sendBinder'
 * dengan extra 'binder' bertipe com.tile.tuoluoyi.BinderContainer.
 *
 * Saat APK BARU (com.lisztomaniaaa.papiano) install + register receiver,
 * sticky broadcast dari daemon lama langsung dikirim ke kita. Tapi class
 * com.tile.tuoluoyi.BinderContainer gak ada di dex baru → unmarshall fail
 * → BadParcelableException → app crash di onCreate setelah register.
 *
 * Stub ini bikin unmarshall sukses. Receiver di MainActivity / tuoluoyiService
 * deteksi class-nya (legacy vs baru) dan ambil IBinder-nya tetep dengan benar.
 *
 * Setelah unmarshall sukses, kita kirim balik 'intent.tuoluoyi.exit' supaya
 * daemon lama mati. User aktivasi ulang -> daemon baru naik -> normal.
 */
public class BinderContainer implements Parcelable {
    private final IBinder binder;

    public BinderContainer(IBinder binder) {
        this.binder = binder;
    }

    public IBinder getBinder() {
        return binder;
    }

    protected BinderContainer(Parcel in) {
        binder = in.readStrongBinder();
    }

    public static final Creator<BinderContainer> CREATOR = new Creator<BinderContainer>() {
        @Override
        public BinderContainer createFromParcel(Parcel in) {
            return new BinderContainer(in);
        }

        @Override
        public BinderContainer[] newArray(int size) {
            return new BinderContainer[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeStrongBinder(binder);
    }
}
