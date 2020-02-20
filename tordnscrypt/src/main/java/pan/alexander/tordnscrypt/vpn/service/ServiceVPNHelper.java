package pan.alexander.tordnscrypt.vpn.service;
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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;

import pan.alexander.tordnscrypt.MainActivity;
import pan.alexander.tordnscrypt.modules.ModulesStatus;
import pan.alexander.tordnscrypt.utils.enums.ModuleState;
import pan.alexander.tordnscrypt.utils.enums.OperationMode;
import pan.alexander.tordnscrypt.utils.enums.VPNCommand;

import static pan.alexander.tordnscrypt.utils.enums.ModuleState.RUNNING;
import static pan.alexander.tordnscrypt.utils.enums.OperationMode.ROOT_MODE;
import static pan.alexander.tordnscrypt.utils.enums.OperationMode.VPN_MODE;
import static pan.alexander.tordnscrypt.vpn.service.ServiceVPN.EXTRA_COMMAND;
import static pan.alexander.tordnscrypt.vpn.service.ServiceVPN.EXTRA_REASON;

public class ServiceVPNHelper {

    public static void start(String reason, Context context) {
        Intent intent = new Intent(context, ServiceVPN.class);
        intent.putExtra(EXTRA_COMMAND, VPNCommand.START);
        intent.putExtra(EXTRA_REASON, reason);
        sendIntent(context, intent);
    }

    public static void reload(String reason, Context context) {
        ModulesStatus modulesStatus = ModulesStatus.getInstance();
        OperationMode operationMode = modulesStatus.getMode();
        ModuleState dnsCryptState = modulesStatus.getDnsCryptState();
        ModuleState torState = modulesStatus.getTorState();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean vpnServiceEnabled = prefs.getBoolean("VPNServiceEnabled", false);

        boolean fixTTL = modulesStatus.isFixTTL() && (modulesStatus.getMode() == ROOT_MODE)
                && !modulesStatus.isUseModulesWithRoot();

        if (((operationMode == VPN_MODE) || fixTTL)
                && vpnServiceEnabled
                && (dnsCryptState == RUNNING || torState == RUNNING)) {
            Intent intent = new Intent(context, ServiceVPN.class);
            intent.putExtra(EXTRA_COMMAND, VPNCommand.RELOAD);
            intent.putExtra(EXTRA_REASON, reason);
            sendIntent(context, intent);
        }
    }

    public static void stop(String reason, Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean vpnServiceEnabled = prefs.getBoolean("VPNServiceEnabled", false);

        if (vpnServiceEnabled) {
            Intent intent = new Intent(context, ServiceVPN.class);
            intent.putExtra(EXTRA_COMMAND, VPNCommand.STOP);
            intent.putExtra(EXTRA_REASON, reason);
            sendIntent(context, intent);
        }
    }

    public static void prepareVPNServiceIfRequired(Activity activity, ModulesStatus modulesStatus) {
        OperationMode operationMode = modulesStatus.getMode();

        boolean fixTTL = modulesStatus.isFixTTL() && (modulesStatus.getMode() == ROOT_MODE)
                && !modulesStatus.isUseModulesWithRoot();

        SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(activity);
        if (((operationMode == VPN_MODE) || fixTTL)
                && activity instanceof MainActivity
                && !prefs.getBoolean("VPNServiceEnabled", false)) {
            ((MainActivity) activity).prepareVPNService();
        }
    }

    private static void sendIntent(Context context, Intent intent) {
        intent.putExtra("showNotification", isShowNotification(context));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private static boolean isShowNotification(Context context) {
        SharedPreferences shPref = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        return shPref.getBoolean("swShowNotification", true);
    }
}