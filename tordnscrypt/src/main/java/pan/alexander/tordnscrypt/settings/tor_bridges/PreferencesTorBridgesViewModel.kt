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

    Copyright 2019-2023 by Garmatin Oleksandr invizible.soft@gmail.com
 */

package pan.alexander.tordnscrypt.settings.tor_bridges

import android.graphics.Bitmap
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import pan.alexander.tordnscrypt.domain.bridges.*
import pan.alexander.tordnscrypt.utils.logger.Logger.loge
import pan.alexander.tordnscrypt.utils.logger.Logger.logw
import javax.inject.Inject
import kotlin.Exception

@ExperimentalCoroutinesApi
class PreferencesTorBridgesViewModel @Inject constructor(
    private val defaultVanillaBridgeInteractor: DefaultVanillaBridgeInteractor,
    private val requestBridgesInteractor: RequestBridgesInteractor,
    private val bridgesCountriesInteractor: BridgesCountriesInteractor
) : ViewModel() {

    private val timeouts = mutableListOf<BridgePingResult>()
    private val timeoutMutableLiveData = MutableLiveData<List<BridgePingResult>>()
    private var timeoutsMeasurementJob: Job? = null
    private var timeoutsObserveJob: Job? = null
    val timeoutLiveData: LiveData<List<BridgePingResult>> get() = timeoutMutableLiveData


    private val defaultVanillaBridgesMutableLiveData = MutableLiveData<List<String>>()
    val defaultVanillaBridgesLiveData: LiveData<List<String>> get() = defaultVanillaBridgesMutableLiveData
    private var relayBridgesRequestJob: Job? = null

    private var torBridgesRequestJob: Job? = null

    private val bridgeCountries = mutableListOf<BridgeCountryData>()
    private val bridgeCountriesMutableLiveData = MutableLiveData<List<BridgeCountryData>>()
    val bridgeCountriesLiveData: LiveData<List<BridgeCountryData>> get() = bridgeCountriesMutableLiveData
    private var bridgeCountriesSearchingJob: Job? = null
    private var bridgeCountriesObserveJob: Job? = null

    private val dialogsFlowMutableLiveData = MutableLiveData<DialogsFlowState>()
    val dialogsFlowLiveData: LiveData<DialogsFlowState> get() = dialogsFlowMutableLiveData.distinctUntilChanged()

    private val errorsMutableLiveData = MutableLiveData<String>()
    val errorsLiveData: LiveData<String> get() = errorsMutableLiveData

    fun measureTimeouts(bridges: List<ObfsBridge>) {

        cancelMeasuringTimeouts()

        if (timeoutsObserveJob?.isCancelled != false) {
            initBridgeCheckerObserver()
        }

        timeoutsMeasurementJob = viewModelScope.launch {
            defaultVanillaBridgeInteractor.measureTimeouts(bridges.map { it.bridge })
        }
    }

    fun cancelMeasuringTimeouts() {
        timeoutsMeasurementJob?.cancelChildren()
        timeouts.clear()
    }

    private fun initBridgeCheckerObserver() {
        timeoutsObserveJob = viewModelScope.launch {
            defaultVanillaBridgeInteractor.observeTimeouts()
                .filter {
                    when (it) {
                        is BridgePingData -> it.ping != 0
                        is PingCheckComplete -> true
                    }
                }.onEach {
                    timeouts.add(it)
                    timeoutMutableLiveData.value = timeouts
                }.collect()
        }
    }

    fun requestRelayBridges(allowIPv6Relays: Boolean) {
        relayBridgesRequestJob?.cancel()
        relayBridgesRequestJob = viewModelScope.launch {
            try {
                defaultVanillaBridgesMutableLiveData.value =
                    defaultVanillaBridgeInteractor.requestRelays(allowIPv6Relays)
                        .map {
                            if (it.address.isIPv6Address()) {
                                "[${it.address}]:${it.port} ${it.fingerprint}"
                            } else {
                                "${it.address}:${it.port} ${it.fingerprint}"
                            }
                        }
            } catch (ignored: CancellationException) {
            } catch (e: Exception) {
                e.message?.let {
                    errorsMutableLiveData.value = it
                }
                loge("PreferencesTorBridgesViewModel requestRelayBridges", e)
            }
        }
    }

    private fun String.isIPv6Address() = contains(":")

    fun cancelRequestingRelayBridges() {
        relayBridgesRequestJob?.cancel()
    }

    fun dismissRequestBridgesDialogs() {
        dialogsFlowMutableLiveData.value = DialogsFlowState.NoDialogs
    }

    fun cancelTorBridgesRequestJob() {
        torBridgesRequestJob?.cancel()
        dialogsFlowMutableLiveData.value = DialogsFlowState.NoDialogs
    }

    fun showSelectRequestBridgesTypeDialog() {
        dialogsFlowMutableLiveData.value = DialogsFlowState.SelectBridgesTransportDialog
    }

    fun requestTorBridgesCaptchaChallenge(transport: String, ipv6Bridges: Boolean) {

        showPleaseWaitDialog()

        torBridgesRequestJob?.cancel()
        torBridgesRequestJob = viewModelScope.launch {
            try {
                val result = requestBridgesInteractor.requestCaptchaChallenge(transport, ipv6Bridges)
                dismissRequestBridgesDialogs()
                showCaptchaDialog(transport, ipv6Bridges, result.first, result.second)
            } catch (e: CancellationException) {
                logw("PreferencesTorBridgesViewModel requestTorBridgesCaptchaChallenge", e)
            } catch (e: java.util.concurrent.CancellationException) {
                logw("PreferencesTorBridgesViewModel requestTorBridgesCaptchaChallenge", e)
            } catch (e: Exception) {
                e.message?.let { showErrorMessage(it) }
                loge("PreferencesTorBridgesViewModel requestTorBridgesCaptchaChallenge", e)
            }
        }
    }

    private fun showCaptchaDialog(transport: String, ipv6Bridges: Boolean, captcha: Bitmap, secretCode: String) {
        dialogsFlowMutableLiveData.value =
            DialogsFlowState.CaptchaDialog(transport, ipv6Bridges, captcha, secretCode)
    }

    fun requestTorBridges(
        transport: String,
        ipv6Bridges: Boolean,
        captchaText: String,
        secretCode: String
    ) {

        showPleaseWaitDialog()

        torBridgesRequestJob?.cancel()
        torBridgesRequestJob = viewModelScope.launch {
            try {
                val result = requestBridgesInteractor.requestBridges(
                    transport,
                    ipv6Bridges,
                    captchaText,
                    secretCode
                )

                dismissRequestBridgesDialogs()

                when (result) {
                    is ParseBridgesResult.BridgesReady ->
                        showBridgesReadyDialog(result.bridges)
                    is ParseBridgesResult.RecaptchaChallenge ->
                        showCaptchaDialog(transport, ipv6Bridges, result.captcha, result.secretCode)
                }
            } catch (e: CancellationException) {
                logw("PreferencesTorBridgesViewModel requestTorBridges", e)
            } catch (e: java.util.concurrent.CancellationException) {
                logw("PreferencesTorBridgesViewModel requestTorBridges", e)
            } catch (e: Exception) {
                e.message?.let { showErrorMessage(it) }
                loge("PreferencesTorBridgesViewModel requestTorBridges", e)
            }
        }
    }

    fun searchBridgeCountries(bridges: List<ObfsBridge>) {

        if (bridges.isEmpty()) {
            return
        }

        cancelSearchingBridgeCountries()

        if (bridgeCountriesObserveJob?.isCancelled != false) {
            initBridgeCountriesObserver()
        }

        bridgeCountriesSearchingJob = viewModelScope.launch {
            bridgesCountriesInteractor.searchBridgeCountries(bridges.map { it.bridge })
        }
    }

    private fun initBridgeCountriesObserver() {
        bridgeCountriesObserveJob = viewModelScope.launch {
            bridgesCountriesInteractor.observeBridgeCountries()
                .onEach {
                    bridgeCountries.add(it)
                    bridgeCountriesMutableLiveData.value = bridgeCountries
                }.collect()
        }
    }

    fun cancelSearchingBridgeCountries() {
        bridgeCountriesSearchingJob?.cancelChildren()
        bridgeCountries.clear()
    }

    private fun showBridgesReadyDialog(bridges: String) {
        dialogsFlowMutableLiveData.value = DialogsFlowState.BridgesReadyDialog(bridges)
    }

    private fun showPleaseWaitDialog() {
        dialogsFlowMutableLiveData.value = DialogsFlowState.PleaseWaitDialog
    }

    private fun showErrorMessage(message: String) {
        dialogsFlowMutableLiveData.value = DialogsFlowState.ErrorMessage(message)
    }
}
