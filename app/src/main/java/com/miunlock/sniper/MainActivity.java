package com.miunlock.sniper;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.miunlock.sniper.core.ApplyResult;
import com.miunlock.sniper.core.HttpSender;
import com.miunlock.sniper.core.Native;
import com.miunlock.sniper.core.Prefs;
import com.miunlock.sniper.core.Schedule;
import com.miunlock.sniper.core.Session;
import com.miunlock.sniper.core.Sniper;
import com.miunlock.sniper.core.TimeSync;
import com.miunlock.sniper.core.Trace;
import com.miunlock.sniper.databinding.ActivityMainBinding;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {

    private static final int REQUEST_NOTIFICATIONS = 101;
    private static final long TICK_MS = 100L;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat localClock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final SimpleDateFormat beijing = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final SimpleDateFormat moscow = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private ActivityMainBinding binding;

    private final Runnable onTick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ticker.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        beijing.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        moscow.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));

        bindTargetSelection();
        bindSettings();
        bindActions();

        binding.textLog.setText(Trace.dump());
        Trace.setListener(this::appendLog);
        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TimeSync.invalidate();
        ticker.removeCallbacks(onTick);
        ticker.post(onTick);
        updateAccountLine();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(onTick);
        Trace.setListener(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    private void bindTargetSelection() {
        final Prefs prefs = Prefs.get(this);
        switch (prefs.targetMode()) {
            case Prefs.MODE_MSK_1700:
                binding.radioMsk1700.setChecked(true);
                break;
            case Prefs.MODE_MSK_1900:
                binding.radioMsk1900.setChecked(true);
                break;
            case Prefs.MODE_CUSTOM:
                binding.radioCustom.setChecked(true);
                break;
            default:
                binding.radioBeijing.setChecked(true);
        }
        binding.radioTarget.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == binding.radioMsk1700.getId()) {
                prefs.setTargetMode(Prefs.MODE_MSK_1700);
            } else if (checkedId == binding.radioMsk1900.getId()) {
                prefs.setTargetMode(Prefs.MODE_MSK_1900);
            } else if (checkedId == binding.radioCustom.getId()) {
                prefs.setTargetMode(Prefs.MODE_CUSTOM);
            } else {
                prefs.setTargetMode(Prefs.MODE_BEIJING);
            }
            refresh();
        });

        binding.buttonPickTarget.setOnClickListener(view -> pickCustomTarget());
    }

    private void bindSettings() {
        final Prefs prefs = Prefs.get(this);
        binding.inputEarly.setText(String.valueOf(prefs.earlyOffsetMs()));
        binding.inputJitterMin.setText(String.valueOf(prefs.jitterMinMs()));
        binding.inputJitterMax.setText(String.valueOf(prefs.jitterMaxMs()));
        binding.inputPreconnect.setText(String.valueOf(prefs.preconnectLeadMs()));
        binding.inputRetryWindow.setText(String.valueOf(prefs.retryWindowMs()));
        binding.inputRetryInterval.setText(String.valueOf(prefs.retryIntervalMs()));
        binding.inputAttempts.setText(String.valueOf(prefs.maxAttempts()));
        binding.inputPrepare.setText(String.valueOf(prefs.prepareLeadMs() / 1000));
        binding.inputServers.setText(prefs.ntpServers());
        binding.buttonApplySettings.setOnClickListener(view -> saveSettings());
    }

    private void saveSettings() {
        final Prefs prefs = Prefs.get(this);
        prefs.setEarlyOffsetMs(readInt(binding.inputEarly, prefs.earlyOffsetMs(), 0, 2000));
        final int jitterMin = readInt(binding.inputJitterMin, prefs.jitterMinMs(), 0, 500);
        final int jitterMax = readInt(binding.inputJitterMax, prefs.jitterMaxMs(), jitterMin, 500);
        prefs.setJitterMs(jitterMin, jitterMax);
        prefs.setPreconnectLeadMs(readInt(binding.inputPreconnect, prefs.preconnectLeadMs(), 0, 5000));
        prefs.setRetryWindowMs(readInt(binding.inputRetryWindow, prefs.retryWindowMs(), 0, 30000));
        prefs.setRetryIntervalMs(readInt(binding.inputRetryInterval, prefs.retryIntervalMs(), 20, 5000));
        prefs.setMaxAttempts(readInt(binding.inputAttempts, prefs.maxAttempts(), 1, 40));
        prefs.setPrepareLeadMs(readInt(binding.inputPrepare, prefs.prepareLeadMs() / 1000, 10, 300) * 1000);
        final String servers = binding.inputServers.getText() == null ? ""
                : binding.inputServers.getText().toString().trim();
        if (!servers.isEmpty()) {
            prefs.setNtpServers(servers);
        }
        Trace.i("UI", "настройки сохранены: опережение " + prefs.earlyOffsetMs() + " мс, разброс " + prefs.jitterMinMs() +
                "-" + prefs.jitterMaxMs() + " мс, прогрев за " + prefs.preconnectLeadMs() + " мс, окно " +
                prefs.retryWindowMs() + " мс, интервал " + prefs.retryIntervalMs() + " мс, попыток " +
                prefs.maxAttempts() + ", подготовка за " + (prefs.prepareLeadMs() / 1000) + " с");
        binding.inputPrepare.setText(String.valueOf(prefs.prepareLeadMs() / 1000));
        refresh();
    }

    private void bindActions() {
        binding.buttonSync.setOnClickListener(view -> {
            binding.buttonSync.setEnabled(false);
            TimeSync.syncAsync(this, (ok, state, error) -> {
                binding.buttonSync.setEnabled(true);
                toast(ok ? "NTP синхронизирован" : "NTP ошибка: " + error);
                refresh();
            });
        });

        binding.buttonArm.setOnClickListener(view -> arm());

        binding.buttonDisarm.setOnClickListener(view -> {
            Sniper.disarm(this);
            toast("Снайпер снят");
            refresh();
        });

        binding.buttonLogin.setOnClickListener(view ->
                startActivity(new Intent(this, LoginActivity.class)));

        binding.buttonCheckState.setOnClickListener(view -> checkState());

        binding.buttonClearLog.setOnClickListener(view -> {
            Trace.clear();
            binding.textLog.setText("");
        });
    }

    private void arm() {
        if (!Native.available()) {
            toast("Нативная библиотека не загружена");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Sniper.canScheduleExact(this)) {
            toast("Разрешите точные будильники");
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception error) {
                Trace.w("UI", "не удалось открыть настройки будильников: " + error.getMessage());
            }
            return;
        }
        try {
            final long target = Sniper.arm(this);
            toast("Взведено на " + Schedule.beijingStamp(target) + " (Пекин)");
        } catch (Sniper.ArmException error) {
            Trace.w("UI", "взвод отменён: " + error.getMessage());
            toast(error.getMessage());
        }
        refresh();
    }

    private void checkState() {
        final Session session = Session.load(this);
        if (!session.hasToken()) {
            toast("Сначала войдите в аккаунт");
            return;
        }
        binding.buttonCheckState.setEnabled(false);
        worker.execute(() -> {
            ApplyResult result;
            try {
                final HttpSender sender = new HttpSender(session);
                try {
                    result = sender.state();
                } finally {
                    sender.shutdown();
                }
            } catch (Exception error) {
                result = ApplyResult.network(-1, String.valueOf(error.getMessage()));
            }
            final ApplyResult finalResult = result;
            runOnUiThread(() -> {
                binding.buttonCheckState.setEnabled(true);
                Trace.i("STATE", finalResult.describe());
                toast(finalResult.describe());
            });
        });
    }

    private void pickCustomTarget() {
        final Prefs prefs = Prefs.get(this);
        final Calendar calendar = Calendar.getInstance();
        final long current = prefs.customTargetMs();
        if (current > 0L) {
            calendar.setTimeInMillis(current);
        }
        new DatePickerDialog(this, (dateView, year, month, day) -> {
            final Calendar picked = Calendar.getInstance();
            picked.set(Calendar.YEAR, year);
            picked.set(Calendar.MONTH, month);
            picked.set(Calendar.DAY_OF_MONTH, day);
            new TimePickerDialog(this, (timeView, hour, minute) -> {
                picked.set(Calendar.HOUR_OF_DAY, hour);
                picked.set(Calendar.MINUTE, minute);
                picked.set(Calendar.SECOND, 0);
                picked.set(Calendar.MILLISECOND, 0);
                prefs.setCustomTargetMs(picked.getTimeInMillis());
                prefs.setTargetMode(Prefs.MODE_CUSTOM);
                binding.radioCustom.setChecked(true);
                refresh();
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show();
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void refresh() {
        final Prefs prefs = Prefs.get(this);
        final long trustedNow = TimeSync.trustedNowMs();

        binding.textClock.setText(localClock.format(new Date(trustedNow)));
        binding.textClockZones.setText("Пекин " + beijing.format(new Date(trustedNow)) + "   МСК " +
                moscow.format(new Date(trustedNow)));

        final long syncAge = TimeSync.offsetAgeMs();
        final String ntpStatus;
        if (!Native.available()) {
            ntpStatus = "нативная библиотека не загружена";
        } else if (syncAge < 0) {
            ntpStatus = "нет синхронизации";
        } else {
            ntpStatus = "смещение " + Trace.signed(TimeSync.offsetMs()) + " мс, точность ±" + TimeSync.uncertaintyMs() +
                    " мс, синхронизация " + (syncAge / 1000L) + " с назад";
        }
        binding.textNtpStatus.setText(ntpStatus);

        final boolean armed = prefs.armed();
        final long target = armed ? prefs.armedTargetMs()
                : Schedule.resolve(prefs.targetMode(), prefs.customTargetMs(), trustedNow);
        if (target <= 0L) {
            binding.textTargetInfo.setText("цель не задана");
            binding.textCountdown.setText("--:--:--.---");
        } else {
            final long remaining = target - trustedNow;
            binding.textCountdown.setText(Schedule.countdown(remaining));
            binding.textTargetInfo.setText("цель " + Schedule.modeLabel(prefs.targetMode()) + " -> Пекин " +
                    Schedule.beijingStamp(target) + ", МСК " + Schedule.moscowStamp(target) +
                    (armed ? "  (ВЗВЕДЕНО)" : "  (расчёт)"));
        }

        if (SniperService.running) {
            binding.textPhase.setText("состояние: " + SniperService.phaseText +
                    (SniperService.lastResultText.isEmpty() ? "" : " | " + SniperService.lastResultText));
        } else if (armed) {
            binding.textPhase.setText("состояние: взведён, ждём будильника");
        } else {
            binding.textPhase.setText("состояние: ожидание");
        }
    }

    private void updateAccountLine() {
        final Session session = Session.load(this);
        if (session.hasToken()) {
            binding.textAccount.setText("вход выполнен: userId " + session.userId + ", источник " +
                    session.source + ", токен от " + Trace.clock(session.savedAtMs) +
                    (Session.storageSecure(this) ? " (хранилище зашифровано)" : " (хранилище без шифрования)"));
        } else {
            binding.textAccount.setText("вход не выполнен");
        }
    }

    private void appendLog(String line) {
        binding.textLog.append(line + "\n");
        final CharSequence text = binding.textLog.getText();
        if (text.length() > 20000) {
            binding.textLog.setText(text.subSequence(text.length() - 15000, text.length()));
        }
        binding.scrollLog.post(() -> binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN));
    }

    private static int readInt(android.widget.EditText field, int fallback, int min, int max) {
        try {
            final CharSequence text = field.getText();
            if (text == null || text.length() == 0) {
                return fallback;
            }
            final int value = Integer.parseInt(text.toString().trim());
            if (value < min) {
                return min;
            }
            return Math.min(value, max);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Trace.w("UI", "уведомления запрещены: прогресс снайпера не будет виден в шторке");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
