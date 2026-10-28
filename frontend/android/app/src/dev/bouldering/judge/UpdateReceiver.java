package dev.bouldering.judge;
import android.content.*;
import android.content.pm.PackageInstaller;
/** Explicit non-exported receiver used only by the system installer callback. */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        android.content.SharedPreferences prefs=context.getSharedPreferences(AppUpdater.PREF,0);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
            prefs.edit().putString("status","Android требует подтверждение установки. Обновление ещё не установлено.").apply();
            Intent confirmation=intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if(confirmation!=null) {
                try{confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(confirmation);}
                catch(Exception ignored){prefs.edit().putString("status","Android требует подтверждение. Откройте приложение и повторите обновление.").apply();}
            }
        } else if(status==PackageInstaller.STATUS_SUCCESS) {
            prefs.edit().putString("status","Android подтвердил успешную установку обновления.").remove("install_version").apply();
        } else {
            prefs.edit().putString("status","Обновление не установлено или отменено (код Android " + status + "). Данные не очищались.").remove("install_version").apply();
        }
    }
}
