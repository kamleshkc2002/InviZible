package pan.alexander.tordnscrypt.dnscrypt_fragment;

/*
    This file is part of InviZible Pro.

    InviZible Pro is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    InviZible Pro is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with InviZible Pro.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2019-2020 by Garmatin Oleksandr invizible.soft@gmail.com
*/

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.text.Html;
import android.util.Log;
import android.widget.Toast;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import pan.alexander.tordnscrypt.MainActivity;
import pan.alexander.tordnscrypt.R;
import pan.alexander.tordnscrypt.TopFragment;
import pan.alexander.tordnscrypt.dialogs.NotificationDialogFragment;
import pan.alexander.tordnscrypt.dialogs.NotificationHelper;
import pan.alexander.tordnscrypt.iptables.ModulesIptablesRules;
import pan.alexander.tordnscrypt.iptables.Tethering;
import pan.alexander.tordnscrypt.modules.ModulesAux;
import pan.alexander.tordnscrypt.modules.ModulesKiller;
import pan.alexander.tordnscrypt.modules.ModulesRunner;
import pan.alexander.tordnscrypt.modules.ModulesStatus;
import pan.alexander.tordnscrypt.settings.PathVars;
import pan.alexander.tordnscrypt.utils.OwnFileReader;
import pan.alexander.tordnscrypt.utils.PrefManager;
import pan.alexander.tordnscrypt.utils.Utils;
import pan.alexander.tordnscrypt.utils.enums.ModuleState;
import pan.alexander.tordnscrypt.vpn.Rule;
import pan.alexander.tordnscrypt.vpn.Util;
import pan.alexander.tordnscrypt.vpn.service.ServiceVPN;
import pan.alexander.tordnscrypt.vpn.service.ServiceVPNHandler;
import pan.alexander.tordnscrypt.vpn.service.ServiceVPNHelper;

import static pan.alexander.tordnscrypt.TopFragment.appVersion;
import static pan.alexander.tordnscrypt.utils.RootExecService.LOG_TAG;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.RESTARTING;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.RUNNING;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.STARTING;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.STOPPED;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.STOPPING;
import static pan.alexander.tordnscrypt.utils.enums.OperationMode.ROOT_MODE;
import static pan.alexander.tordnscrypt.utils.enums.OperationMode.VPN_MODE;

public class DNSCryptFragmentPresenter implements DNSCryptFragmentPresenterCallbacks {
    private boolean bound;

    private int displayLogPeriod = -1;

    private DNSCryptFragmentView view;
    private ScheduledFuture<?> scheduledFuture;
    private volatile OwnFileReader logFile;
    private ModulesStatus modulesStatus;
    private ModuleState fixedModuleState;
    private ServiceConnection serviceConnection;
    private ServiceVPN serviceVPN;
    private volatile LinkedList<DNSQueryLogRecord> savedDNSQueryRawRecords;
    private volatile DNSQueryLogRecords dnsQueryLogRecords;
    private boolean torTethering;
    private boolean apIsOn;
    private String localEthernetDeviceAddress = "192.168.0.100";
    private boolean dnsCryptLogAutoScroll = true;
    private boolean meteredNetwork = true;

    public DNSCryptFragmentPresenter(DNSCryptFragmentView view) {
        this.view = view;
    }

    public void onStart(Context context) {
        if (context == null || view == null) {
            return;
        }

        PathVars pathVars = PathVars.getInstance(context);
        String appDataDir = pathVars.getAppDataDir();

        modulesStatus = ModulesStatus.getInstance();

        savedDNSQueryRawRecords = new LinkedList<>();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        torTethering = sharedPreferences.getBoolean("pref_common_tor_tethering", false);
        localEthernetDeviceAddress = sharedPreferences.getString("pref_common_local_eth_device_addr", "192.168.0.100");
        apIsOn = new PrefManager(context).getBoolPref("APisON");
        boolean blockIPv6 = sharedPreferences.getBoolean("block_ipv6", true);

        logFile = new OwnFileReader(context, appDataDir + "/logs/DnsCrypt.log");

        if (isDNSCryptInstalled(context)) {
            setDNSCryptInstalled(true);

            if (modulesStatus.getDnsCryptState() == STOPPING) {
                setDnsCryptStopping();

                displayLog(1);
            } else if (isSavedDNSStatusRunning(context) || modulesStatus.getDnsCryptState() == RUNNING) {
                setDnsCryptRunning();

                if (modulesStatus.getDnsCryptState() != RESTARTING) {
                    modulesStatus.setDnsCryptState(RUNNING);
                }

                displayLog(1);

            } else {
                setDnsCryptStopped();
                modulesStatus.setDnsCryptState(STOPPED);
            }

        } else {
            setDNSCryptInstalled(false);
        }

        dnsQueryLogRecords = new DNSQueryLogRecords(blockIPv6, pathVars.getDNSCryptFallbackRes());

        meteredNetwork = Util.isMeteredNetwork(context);
    }

    public void onStop(Context context) {

        boolean fixTTL = modulesStatus.isFixTTL() && (modulesStatus.getMode() == ROOT_MODE)
                && !modulesStatus.isUseModulesWithRoot();

        if (new PrefManager(context).getBoolPref("DNSCryptSystemDNSAllowed")) {
            if (modulesStatus.getMode() == ROOT_MODE) {
                new PrefManager(context).setBoolPref("DNSCryptSystemDNSAllowed", false);
                ModulesIptablesRules.denySystemDNS(context);
            }

            if (modulesStatus.getMode() == VPN_MODE || fixTTL) {
                new PrefManager(context).setBoolPref("DNSCryptSystemDNSAllowed", false);
                ServiceVPNHelper.reload("DNSCrypt Deny system DNS", context);
            }
        }

        stopDisplayLog();
        unbindVPNService(context);
        view = null;
    }

    @Override
    public boolean isDNSCryptInstalled(Context context) {
        if (context == null) {
            return false;
        }

        return new PrefManager(context).getBoolPref("DNSCrypt Installed");
    }

    @Override
    public boolean isSavedDNSStatusRunning(Context context) {
        return new PrefManager(context).getBoolPref("DNSCrypt Running");
    }

    @Override
    public void saveDNSStatusRunning(Context context, boolean running) {
        new PrefManager(context).setBoolPref("DNSCrypt Running", running);
    }

    @Override
    public void displayLog(int period) {

        if (period == displayLogPeriod) {
            return;
        }

        displayLogPeriod = period;

        if (scheduledFuture != null && !scheduledFuture.isCancelled()) {
            scheduledFuture.cancel(false);
        }

        ScheduledExecutorService timer = TopFragment.getModulesLogsTimer();

        if (timer == null || timer.isShutdown()) {
            return;
        }

        scheduledFuture = timer.scheduleAtFixedRate(new Runnable() {

            int loop = 0;
            String previousLastLines = "";

            @Override
            public void run() {
                try {
                    if (view == null || view.getFragmentActivity() == null || view.getFragmentActivity().isFinishing() || logFile == null) {
                        return;
                    }

                    final String lastLines = logFile.readLastLines();

                    if (++loop > 120) {
                        loop = 0;

                        if (modulesStatus != null && (modulesStatus.getMode() == VPN_MODE
                                || modulesStatus.getMode() == ROOT_MODE && modulesStatus.isFixTTL() && !modulesStatus.isUseModulesWithRoot())) {
                            displayLog(5);
                        } else {
                            displayLog(10);
                        }
                    }

                    final boolean displayed = displayDnsResponses(lastLines);

                    if (view == null || view.getFragmentActivity() == null || view.getFragmentActivity().isFinishing() || logFile == null) {
                        return;
                    }

                    view.getFragmentActivity().runOnUiThread(() -> {

                        if (view == null || view.getFragmentActivity() == null || view.getFragmentActivity().isFinishing() || lastLines == null || lastLines.isEmpty()) {
                            return;
                        }

                        if (!previousLastLines.contentEquals(lastLines) && dnsCryptLogAutoScroll) {

                            dnsCryptStartedSuccessfully(lastLines);

                            dnsCryptStartedWithError(view.getFragmentActivity(), lastLines);

                            if (!displayed) {
                                view.setDNSCryptLogViewText(Html.fromHtml(lastLines));
                                view.scrollDNSCryptLogViewToBottom();
                            }

                            previousLastLines = lastLines;
                        }

                        refreshDNSCryptState(view.getFragmentActivity());

                    });

                } catch (Exception e) {
                    Log.e(LOG_TAG, "DNSCryptFragmentPresenter timer run() exception " + e.getMessage() + " " + e.getCause());
                }
            }
        }, 1, period, TimeUnit.SECONDS);

    }

    @Override
    public void stopDisplayLog() {
        if (scheduledFuture != null && !scheduledFuture.isCancelled()) {
            scheduledFuture.cancel(false);

            displayLogPeriod = -1;
        }
    }

    private void setDnsCryptStarting() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSStarting, R.color.textModuleStatusColorStarting);
    }

    @Override
    public void setDnsCryptRunning() {
        if (view == null || view.getFragmentActivity() == null || view.getFragmentActivity().isFinishing()) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSRunning, R.color.textModuleStatusColorRunning);
        view.setStartButtonText(R.string.btnDNSCryptStop);

        boolean fixTTL = modulesStatus.isFixTTL() && (modulesStatus.getMode() == ROOT_MODE)
                && !modulesStatus.isUseModulesWithRoot();

        if (new PrefManager(view.getFragmentActivity()).getBoolPref("DNSCryptSystemDNSAllowed")) {
            if (modulesStatus.getMode() == ROOT_MODE) {
                new PrefManager(view.getFragmentActivity()).setBoolPref("DNSCryptSystemDNSAllowed", false);
                ModulesIptablesRules.denySystemDNS(view.getFragmentActivity());
            }

            if (modulesStatus.getMode() == VPN_MODE || fixTTL) {
                new PrefManager(view.getFragmentActivity()).setBoolPref("DNSCryptSystemDNSAllowed", false);
                ServiceVPNHelper.reload("DNSCrypt Deny system DNS", view.getFragmentActivity());
            }

        }
    }

    private void setDnsCryptStopping() {
        if (view == null || view.getFragmentActivity() == null || view.getFragmentActivity().isFinishing()) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSStopping, R.color.textModuleStatusColorStopping);
    }

    @Override
    public void setDnsCryptStopped() {
        if (view == null || view.getFragmentActivity() == null || view.getFragmentActivity().isFinishing()) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSStop, R.color.textModuleStatusColorStopped);
        view.setStartButtonText(R.string.btnDNSCryptStart);
        view.setDNSCryptLogViewText();
    }

    @Override
    public void setDnsCryptInstalling() {
        if (view == null || view.getFragmentActivity() == null || view.getFragmentActivity().isFinishing()) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSInstalling, R.color.textModuleStatusColorInstalling);
    }

    @Override
    public void setDnsCryptInstalled() {
        if (view == null || view.getFragmentActivity() == null || view.getFragmentActivity().isFinishing()) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSInstalled, R.color.textModuleStatusColorInstalled);
    }

    @Override
    public void setDNSCryptStartButtonEnabled(boolean enabled) {
        if (view == null || view.getFragmentActivity() == null || view.getFragmentActivity().isFinishing()) {
            return;
        }

        view.setDNSCryptStartButtonEnabled(enabled);
    }

    @Override
    public void setDNSCryptProgressBarIndeterminate(boolean indeterminate) {
        if (view == null || view.getFragmentActivity() == null || view.getFragmentActivity().isFinishing()) {
            return;
        }

        view.setDNSCryptProgressBarIndeterminate(indeterminate);
    }

    private void setDNSCryptInstalled(boolean installed) {
        if (view == null || view.getFragmentActivity() == null || view.getFragmentActivity().isFinishing()) {
            return;
        }

        if (installed) {
            view.setDNSCryptStartButtonEnabled(true);
        } else {
            view.setDNSCryptStatus(R.string.tvDNSNotInstalled, R.color.textModuleStatusColorAlert);
        }
    }

    @Override
    public void setDnsCryptSomethingWrong() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.wrong, R.color.textModuleStatusColorAlert);
    }

    private void dnsCryptStartedSuccessfully(String lines) {

        if (view == null || modulesStatus == null) {
            return;
        }

        if ((modulesStatus.getDnsCryptState() == STARTING
                || modulesStatus.getDnsCryptState() == RUNNING)
                && lines.contains("lowest initial latency")) {

            if (!modulesStatus.isUseModulesWithRoot()) {
                view.setDNSCryptProgressBarIndeterminate(false);
            }

            setDnsCryptRunning();
        }
    }

    private void dnsCryptStartedWithError(Context context, String lastLines) {

        if (context == null || view == null || view.getFragmentActivity().isFinishing()) {
            return;
        }

        FragmentManager fragmentManager = view.getFragmentFragmentManager();

        if ((lastLines.contains("connect: connection refused")
                || lastLines.contains("ERROR"))
                && !lastLines.contains(" OK ")) {
            Log.e(LOG_TAG, "DNSCrypt Error: " + lastLines);

            if (fragmentManager != null) {
                NotificationHelper notificationHelper = NotificationHelper.setHelperMessage(
                        context, context.getText(R.string.helper_dnscrypt_no_internet).toString(), "helper_dnscrypt_no_internet");
                if (notificationHelper != null) {
                    notificationHelper.show(fragmentManager, NotificationHelper.TAG_HELPER);
                }
            }

        } else if (lastLines.contains("[CRITICAL]") && lastLines.contains("[FATAL]")) {

            if (fragmentManager != null) {
                NotificationHelper notificationHelper = NotificationHelper.setHelperMessage(
                        context, context.getText(R.string.helper_dnscrypt_no_internet).toString(), "helper_dnscrypt_no_internet");
                if (notificationHelper != null) {
                    notificationHelper.show(fragmentManager, NotificationHelper.TAG_HELPER);
                }
            }

            Log.e(LOG_TAG, "DNSCrypt FATAL Error: " + lastLines);

            stopDNSCrypt(context);
        }
    }

    private boolean displayDnsResponses(String savedLines) {

        if (view == null || modulesStatus == null) {
            return false;
        }

        boolean fixTTL = modulesStatus.isFixTTL() && (modulesStatus.getMode() == ROOT_MODE)
                && !modulesStatus.isUseModulesWithRoot();

        if (modulesStatus.getMode() != VPN_MODE && !fixTTL) {
            if (!savedDNSQueryRawRecords.isEmpty() && view != null && view.getFragmentActivity() != null) {
                savedDNSQueryRawRecords.clear();
                view.getFragmentActivity().runOnUiThread(() -> {
                    if (view != null && view.getFragmentActivity() != null && !view.getFragmentActivity().isFinishing() && logFile != null) {
                        view.setDNSCryptLogViewText(Html.fromHtml(logFile.readLastLines()));
                        view.scrollDNSCryptLogViewToBottom();
                    }
                });
                return true;
            } else {
                return false;
            }

        } else if (view != null && view.getFragmentActivity() != null
                && !view.getFragmentActivity().isFinishing()
                && (modulesStatus.getMode() == VPN_MODE || fixTTL) && !bound) {
            bindToVPNService(view.getFragmentActivity());
            return false;
        }

        if (modulesStatus.getDnsCryptState() == RESTARTING) {
            clearDnsQueryRecords();
            savedDNSQueryRawRecords.clear();
            return false;
        }

        if (!dnsCryptLogAutoScroll) {
            return true;
        }

        lockDnsQueryRawRecordsListForRead(true);

        LinkedList<DNSQueryLogRecord> dnsQueryRawRecords = getDnsQueryRawRecords();

        if (dnsQueryRawRecords.equals(savedDNSQueryRawRecords) || dnsQueryRawRecords.isEmpty()) {
            lockDnsQueryRawRecordsListForRead(false);
            return false;
        }

        savedDNSQueryRawRecords = new LinkedList<>(dnsQueryRawRecords);

        lockDnsQueryRawRecordsListForRead(false);

        LinkedList<DNSQueryLogRecord> dnsQueryLogRecords = dnsQueryRawRecordsToLogRecords(savedDNSQueryRawRecords);

        DNSQueryLogRecord record;
        StringBuilder lines = new StringBuilder();

        lines.append(savedLines);

        lines.append("<br />");

        for (int i = 0; i < dnsQueryLogRecords.size(); i++) {
            record = dnsQueryLogRecords.get(i);

            if (appVersion.startsWith("g") && record.getBlockedByIpv6()) {
                continue;
            }

            if (record.getBlocked()) {
                if (!record.getAName().isEmpty()) {
                    lines.append("<font color=#f08080>").append(record.getAName().toLowerCase());

                    if (record.getBlockedByIpv6()) {
                        lines.append(" ipv6");
                    }

                    lines.append("</font>");
                } else {
                    lines.append("<font color=#f08080>").append(record.getQName().toLowerCase()).append("</font>");
                }
            } else {

                if (record.getUid() != -1000 && !record.getDaddr().isEmpty()) {
                    lines.append("<font color=#E7AD42>");
                } else {
                    lines.append("<font color=#009688>");
                }

                if (record.getUid() != -1000) {
                    if (view != null && view.getFragmentActivity() != null && !view.getFragmentActivity().isFinishing()) {

                        String appName = "";

                        List<Rule> appList = ServiceVPNHandler.getAppsList();

                        if (appList != null) {
                            for (Rule rule : appList) {
                                if (rule.uid == record.getUid()) {
                                    appName = rule.appName;
                                    break;
                                }
                            }
                        }

                        if (appName.isEmpty() || record.getUid() == 1000) {
                            appName = view.getFragmentActivity().getPackageManager().getNameForUid(record.getUid());
                        }

                        if (appName != null && !appName.isEmpty()) {
                            lines.append("<b>").append(appName).append("</b>").append(" -> ");
                        } else if (!torTethering && !apIsOn && !Tethering.usbTetherOn && !Tethering.ethernetOn && !fixTTL) {
                            lines.append("<b>").append("Unknown system traffic").append("</b>").append(" -> ");
                        } else if (apIsOn && fixTTL && record.getSaddr().contains("192.168.43.")) {
                            lines.append("<b>").append("WiFi").append("</b>").append(" -> ");
                        } else if (Tethering.usbTetherOn && fixTTL && record.getSaddr().contains("192.168.42.")) {
                            lines.append("<b>").append("USB").append("</b>").append(" -> ");
                        } else if (Tethering.ethernetOn && fixTTL && record.getSaddr().contains(localEthernetDeviceAddress)) {
                            lines.append("<b>").append("LAN").append("</b>").append(" -> ");
                        }
                    }
                }

                if (!record.getAName().isEmpty()) {
                    lines.append(record.getAName().toLowerCase());

                    if (record.getDaddr().contains(":")) {
                        lines.append(" ipv6");
                    }
                }

                if (!record.getCName().isEmpty()) {
                    lines.append(" -> ").append(record.getCName().toLowerCase());
                }

                if (!record.getDaddr().isEmpty()) {

                    if (record.getUid() == -1000) {
                        lines.append(" -> ");
                    }

                    if (!meteredNetwork && record.getUid() != -1000) {
                        String ip = record.getDaddr().split(", ")[0];
                        String host = Utils.INSTANCE.getHostByIP(ip);
                        if (!host.isEmpty() && !host.equals(ip)) {
                            lines.append(host).append(" -> ");
                        }
                    }

                    lines.append(record.getDaddr());
                }

                lines.append("</font>");
            }

            if (i < dnsQueryLogRecords.size() - 1) {
                lines.append("<br />");
            }
        }

        if (view != null && view.getFragmentActivity() != null && !view.getFragmentActivity().isFinishing()) {
            view.getFragmentActivity().runOnUiThread(() -> {
                if (view != null && view.getFragmentActivity() != null && dnsCryptLogAutoScroll) {
                    view.setDNSCryptLogViewText(Html.fromHtml(lines.toString()));
                    view.scrollDNSCryptLogViewToBottom();
                } else {
                    savedDNSQueryRawRecords.clear();
                }
            });
        }

        return true;
    }

    private LinkedList<DNSQueryLogRecord> getDnsQueryRawRecords() {
        if (serviceVPN != null) {
            return serviceVPN.getDnsQueryRawRecords();
        }
        return new LinkedList<>();
    }

    private void clearDnsQueryRecords() {
        if (serviceVPN != null) {
            serviceVPN.clearDnsQueryRawRecords();
        }
    }

    private void lockDnsQueryRawRecordsListForRead(boolean lock) {
        if (serviceVPN != null) {
            serviceVPN.lockDnsQueryRawRecordsListForRead(lock);
        }
    }

    private LinkedList<DNSQueryLogRecord> dnsQueryRawRecordsToLogRecords(LinkedList<DNSQueryLogRecord> dnsQueryRawRecords) {
        return dnsQueryLogRecords.convertRecords(dnsQueryRawRecords);
    }

    @Override
    public void refreshDNSCryptState(Context context) {

        if (context == null || modulesStatus == null || view == null || view.getFragmentActivity() == null || view.getFragmentActivity().isFinishing()) {
            return;
        }

        ModuleState currentModuleState = modulesStatus.getDnsCryptState();

        if (currentModuleState.equals(fixedModuleState) && currentModuleState != STOPPED) {
            return;
        }

        if (currentModuleState == STARTING) {

            displayLog(1);

        } else if (currentModuleState == RUNNING) {

            ServiceVPNHelper.prepareVPNServiceIfRequired(view.getFragmentActivity(), modulesStatus);

            view.setDNSCryptStartButtonEnabled(true);

            saveDNSStatusRunning(context, true);

            view.setStartButtonText(R.string.btnDNSCryptStop);

            displayLog(1);

            if (modulesStatus.getMode() == VPN_MODE && !bound) {
                bindToVPNService(context);
            }

        } else if (currentModuleState == STOPPED) {

            stopDisplayLog();

            if (isSavedDNSStatusRunning(context)) {
                setDNSCryptStoppedBySystem(context);
            } else {
                setDnsCryptStopped();
            }

            view.setDNSCryptProgressBarIndeterminate(false);

            saveDNSStatusRunning(context, false);

            view.setDNSCryptStartButtonEnabled(true);
        }

        fixedModuleState = currentModuleState;
    }

    private void setDNSCryptStoppedBySystem(Context context) {
        if (view == null) {
            return;
        }

        setDnsCryptStopped();

        FragmentManager fragmentManager = view.getFragmentFragmentManager();

        if (context != null && modulesStatus != null) {

            modulesStatus.setDnsCryptState(STOPPED);

            ModulesAux.requestModulesStatusUpdate(context);

            if (fragmentManager != null) {
                DialogFragment notification = NotificationDialogFragment.newInstance(R.string.helper_dnscrypt_stopped);
                notification.show(fragmentManager, "NotificationDialogFragment");
            }

            Log.e(LOG_TAG, context.getText(R.string.helper_dnscrypt_stopped).toString());
        }

    }

    private void runDNSCrypt(Context context) {
        if (context == null || view == null || view.getFragmentActivity() == null || view.getFragmentActivity().isFinishing()) {
            return;
        }

        if (Util.isPrivateDns(context)) {
            FragmentManager fragmentManager = view.getFragmentFragmentManager();
            if (fragmentManager != null) {
                NotificationHelper notificationHelper = NotificationHelper.setHelperMessage(
                        context, context.getText(R.string.helper_dnscrypt_private_dns).toString(), "helper_dnscrypt_private_dns");
                if (notificationHelper != null) {
                    notificationHelper.show(fragmentManager, NotificationHelper.TAG_HELPER);
                }
            }
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);

        if (!sharedPreferences.getBoolean("ignore_system_dns", false)) {
            new PrefManager(context).setBoolPref("DNSCryptSystemDNSAllowed", true);
        }

        ModulesRunner.runDNSCrypt(context);
    }

    private void stopDNSCrypt(Context context) {
        if (context == null) {
            return;
        }

        if (modulesStatus.getMode() == VPN_MODE) {
            clearDnsQueryRecords();
        }

        ModulesKiller.stopDNSCrypt(context);
    }

    private void bindToVPNService(Context context) {
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                serviceVPN = ((ServiceVPN.VPNBinder) service).getService();
                bound = true;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                bound = false;
            }
        };

        if (context != null) {
            Intent intent = new Intent(context, ServiceVPN.class);
            context.bindService(intent, serviceConnection, 0);
        }
    }

    private void unbindVPNService(Context context) {
        if (bound && serviceConnection != null && context != null) {
            context.unbindService(serviceConnection);
            bound = false;
        }
    }

    public void startButtonOnClick(Context context) {
        if (context == null || view == null || modulesStatus == null) {
            return;
        }

        if (((MainActivity) context).childLockActive) {
            Toast.makeText(context, context.getText(R.string.action_mode_dialog_locked), Toast.LENGTH_LONG).show();
            return;
        }


        view.setDNSCryptStartButtonEnabled(false);


        if (new PrefManager(context).getBoolPref("Tor Running")
                && !new PrefManager(context).getBoolPref("DNSCrypt Running")) {

            if (modulesStatus.isContextUIDUpdateRequested()) {
                Toast.makeText(context, R.string.please_wait, Toast.LENGTH_SHORT).show();
                view.setDNSCryptStartButtonEnabled(true);
                return;
            }

            setDnsCryptStarting();

            runDNSCrypt(context);

            displayLog(1);
        } else if (!new PrefManager(context).getBoolPref("Tor Running")
                && !new PrefManager(context).getBoolPref("DNSCrypt Running")) {

            if (modulesStatus.isContextUIDUpdateRequested()) {
                Toast.makeText(context, R.string.please_wait, Toast.LENGTH_SHORT).show();
                view.setDNSCryptStartButtonEnabled(true);
                return;
            }

            setDnsCryptStarting();

            runDNSCrypt(context);

            displayLog(1);
        } else if (!new PrefManager(context).getBoolPref("Tor Running")
                && new PrefManager(context).getBoolPref("DNSCrypt Running")) {
            setDnsCryptStopping();
            stopDNSCrypt(context);
        } else if (new PrefManager(context).getBoolPref("Tor Running")
                && new PrefManager(context).getBoolPref("DNSCrypt Running")) {

            setDnsCryptStopping();
            stopDNSCrypt(context);
        }

        view.setDNSCryptProgressBarIndeterminate(true);
    }

    public void dnsCryptLogAutoScrollingAllowed(boolean allowed) {
        dnsCryptLogAutoScroll = allowed;
    }

}
