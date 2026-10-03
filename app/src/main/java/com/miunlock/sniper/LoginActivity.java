package com.miunlock.sniper;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.miunlock.sniper.core.ApplyResult;
import com.miunlock.sniper.core.Session;
import com.miunlock.sniper.core.Trace;
import com.miunlock.sniper.core.XiaomiAccount;
import com.miunlock.sniper.databinding.ActivityLoginBinding;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LoginActivity extends AppCompatActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService verifier = Executors.newSingleThreadExecutor();

    private ActivityLoginBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.buttonSignIn.setOnClickListener(view -> signIn());
        binding.buttonSaveToken.setOnClickListener(view -> saveManualToken());
        binding.buttonVerify.setOnClickListener(view -> verify());
        binding.buttonLogout.setOnClickListener(view -> logout());
        binding.buttonBack.setOnClickListener(view -> finish());

        updateStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        verifier.shutdownNow();
    }

    private void signIn() {
        final String username = text(binding.inputUsername);
        final String password = text(binding.inputPassword);
        final String sid = text(binding.inputSid).isEmpty() ? XiaomiAccount.SID_DEFAULT : text(binding.inputSid);
        setBusy(true, "вход выполняется...");
        worker.execute(() -> {
            try {
                final Session session = XiaomiAccount.login(getApplicationContext(), username, password, sid);
                Session.save(getApplicationContext(), session);
                main.post(() -> {
                    setBusy(false, "");
                    Trace.i("LOGIN", "сессия сохранена, устройство " + session.deviceId);
                    updateStatus();
                    toast("Вход выполнен");
                });
            } catch (XiaomiAccount.LoginException error) {
                main.post(() -> {
                    setBusy(false, "");
                    Trace.e("LOGIN", "вход не выполнен: " + error.getMessage() + " " + error.hint);
                    binding.textLoginStatus.setText("ошибка: " + error.getMessage() + "\n" + error.hint);
                    toast("Вход не выполнен");
                });
            } catch (Exception error) {
                main.post(() -> {
                    setBusy(false, "");
                    Trace.e("LOGIN", "сбой входа", error);
                    binding.textLoginStatus.setText("сбой: " + error.getMessage());
                    toast("Сбой входа");
                });
            }
        });
    }

    private void saveManualToken() {
        final String token = text(binding.inputManualToken);
        if (token.isEmpty()) {
            toast("Вставьте значение new_bbs_serviceToken");
            return;
        }
        final Session session = Session.load(getApplicationContext());
        session.serviceToken = token;
        session.source = "manual";
        session.deviceId = session.ensureDeviceId(getApplicationContext());
        Session.save(getApplicationContext(), session);
        Trace.i("LOGIN", "токен сохранён вручную, длина " + token.length());
        updateStatus();
        toast("Токен сохранён");
    }

    private void verify() {
        final Session session = Session.load(getApplicationContext());
        if (!session.hasToken()) {
            toast("Нет токена");
            return;
        }
        setBusy(true, "проверка токена...");
        verifier.execute(() -> {
            ApplyResult result;
            try {
                result = XiaomiAccount.verify(getApplicationContext(), session);
            } catch (Exception error) {
                result = ApplyResult.network(-1, String.valueOf(error.getMessage()));
            }
            final ApplyResult finalResult = result;
            main.post(() -> {
                setBusy(false, "");
                Trace.i("LOGIN", "проверка токена: " + finalResult.describe());
                binding.textLoginStatus.setText(finalResult.describe());
                updateStatus();
            });
        });
    }

    private void logout() {
        final Session session = Session.load(getApplicationContext());
        verifier.execute(() -> {
            try {
                if (session.hasToken()) {
                    XiaomiAccount.logout(session);
                }
            } catch (Exception error) {
                Trace.w("LOGIN", "выход на сервере не подтверждён: " + error.getMessage());
            }
            Session.clear(getApplicationContext());
            main.post(() -> {
                Trace.i("LOGIN", "сессия удалена");
                updateStatus();
                toast("Вы вышли");
            });
        });
    }

    private void updateStatus() {
        final Session session = Session.load(getApplicationContext());
        if (session.hasToken()) {
            binding.textLoginStatus.setText("сессия активна: userId " + session.userId + ", cUserId " + session.cUserId +
                    ", источник " + session.source + ", устройство " + session.deviceId);
        } else {
            binding.textLoginStatus.setText("сессия отсутствует");
        }
    }

    private void setBusy(boolean busy, String status) {
        binding.buttonSignIn.setEnabled(!busy);
        if (busy) {
            binding.textLoginStatus.setText(status);
        }
    }

    private static String text(android.widget.EditText field) {
        final CharSequence value = field.getText();
        return value == null ? "" : value.toString().trim();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
