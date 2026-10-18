package com.example.mochi_pet.platform.browser

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.mochi_pet.core.browser.BrowserLogin
import com.example.mochi_pet.core.browser.BrowserLoginKind
import com.example.mochi_pet.core.browser.browserOrigin
import com.example.mochi_pet.core.browser.isBrowserPhoneNumber
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

class BrowserCredentialSaveRequest(val id: String, val login: BrowserLogin) {
    override fun toString() = "BrowserCredentialSaveRequest([redacted])"
}

class BrowserCredentialCapture(private val userControlled: () -> Boolean) {
    private val mutablePending = MutableStateFlow<BrowserCredentialSaveRequest?>(null)
    val pending = mutablePending.asStateFlow()
    private val passwords = linkedSetOf<String>()
    private var redactionOverflow = false

    @Synchronized internal fun protect(password: String) {
        if (passwords.size >= 32 && password !in passwords) redactionOverflow = true
        else passwords += password
    }

    @Synchronized internal fun redact(text: String): String {
        check(!redactionOverflow) { "Start a new browser task before reading this page." }
        return passwords.filter(String::isNotEmpty).sortedByDescending(String::length)
            .fold(text) { value, password -> value.replace(password, "[redacted]") }
    }

    fun dismiss(id: String) {
        if (mutablePending.value?.id == id) mutablePending.value = null
    }

    fun clear() { mutablePending.value = null }
    @Synchronized internal fun reset() { clear(); passwords.clear(); redactionOverflow = false }

    internal fun attach(view: WebView): Boolean {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                val bridge = "mochiCredential" + UUID.randomUUID().toString().replace("-", "")
                WebViewCompat.addWebMessageListener(view, bridge, setOf("*")) { current, message, source, mainFrame, _ ->
                    if (!userControlled() || mutablePending.value != null) return@addWebMessageListener
                    try {
                        require(message.type == androidx.webkit.WebMessageCompat.TYPE_STRING) { "Only text credential messages are accepted." }
                        val login = parseCredentialSubmission(
                            message.data.orEmpty(), source.toString(), current.url.orEmpty(), mainFrame,
                        )
                        if (login.password.isNotEmpty()) protect(login.password)
                        mutablePending.value = BrowserCredentialSaveRequest(UUID.randomUUID().toString(), login)
                    } catch (_: IllegalArgumentException) {
                        Log.w("MochiBrowser", "Rejected invalid or cross-origin credential submission")
                    }
                }
                WebViewCompat.addDocumentStartJavaScript(view, credentialCaptureScript(bridge), setOf("*"))
                return true
            }
        }
        return false
    }
}

internal fun parseCredentialSubmission(payload: String, source: String, currentUrl: String, mainFrame: Boolean): BrowserLogin {
    require(mainFrame && browserOrigin(source) == browserOrigin(currentUrl)) { "Credential origin mismatch." }
    require(payload.length <= 32_768) { "Credential message too large." }
    val fields = Json.parseToJsonElement(payload).jsonObject
    val username = fields["username"]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull
    val kind = fields["kind"]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull
    require(!fields.containsKey("kind") || kind != null) { "Invalid login kind." }
    if (kind == "sms") {
        require(fields.keys == setOf("kind", "username") && username != null && isBrowserPhoneNumber(username)) {
            "SMS login messages may contain only the phone number."
        }
        return BrowserLogin(browserOrigin(source), username, kind = BrowserLoginKind.SMS)
    }
    require((kind == null || kind == "password") && fields.keys.all { it in setOf("kind", "username", "password") }) {
        "Invalid credential message."
    }
    val password = fields["password"]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull
    require(username != null && username.length <= 320 && password != null && password.length in 1..4_096) {
        "Invalid credential fields."
    }
    return BrowserLogin(browserOrigin(source), username, password)
}

private val credentialFieldHelpers = """
    const hint = el => [el.name, el.id, el.autocomplete, el.placeholder,
      el.getAttribute('aria-label'), ...(Array.from(el.labels || []).map(label => label.textContent.slice(0, 200)))]
      .join(' ').toLowerCase();
    const isCode = el => /one-time-code|otp|sms.*code|verify.*code|verification|vcode|captcha|验证码|校验码|动态码/.test(hint(el));
    const isPhone = el => !isCode(el) && (el.type === 'tel' || /(^|\s)tel(-|\s|$)|mobile|phone|手机号|手机号码/.test(hint(el)));
""".trimIndent()

internal fun credentialCaptureScript(bridge: String): String = """
    (() => {
      if (window !== window.top) return;
      $credentialFieldHelpers
      const visible = el => !el.disabled && el.getClientRects().length > 0 &&
        getComputedStyle(el).visibility !== 'hidden';
      const capture = scope => {
        const fields = Array.from(scope instanceof HTMLFormElement ? scope.elements : scope.querySelectorAll('input'))
          .filter(el => el instanceof HTMLInputElement && visible(el));
        const passwords = fields.filter(el => el.type === 'password' && !isCode(el) && el.value);
        if (passwords.length) {
          if (passwords.some(el => el.value !== passwords[0].value)) return;
          const candidates = fields.filter(el => ['text', 'email', 'tel'].includes(el.type) && !isCode(el) && el.value);
          const username = candidates.find(el => el.autocomplete === 'username') ||
            candidates.find(el => el.type === 'email' || /user|login|email|account/i.test(el.name + ' ' + el.id)) ||
            candidates.filter(el => fields.indexOf(el) < fields.indexOf(passwords[0])).pop();
          window[${JsonPrimitive(bridge)}].postMessage(JSON.stringify({
            kind: 'password', username: (username?.value || '').slice(0, 321),
            password: passwords[0].value.slice(0, 4097)
          }));
        } else {
          const phones = fields.filter(isPhone);
          if (phones.length !== 1 || !fields.some(el => isCode(el) && el.value.length > 0)) return;
          window[${JsonPrimitive(bridge)}].postMessage(JSON.stringify({
            kind: 'sms', username: phones[0].value.trim().slice(0, 321)
          }));
        }
      };
      document.addEventListener('submit', event => {
        if (event.isTrusted && event.target instanceof HTMLFormElement) capture(event.target);
      }, true);
      document.addEventListener('click', event => {
        if (!event.isTrusted || !(event.target instanceof Element)) return;
        const button = event.target.closest('button,input[type="submit"],input[type="button"],[role="button"],a');
        if (!button || !visible(button)) return;
        const label = (button.textContent || button.value || button.getAttribute('aria-label') || '').replace(/\s+/g, '').toLowerCase();
        if (/send|resend|get.*code|获取|发送|验证码登录|短信登录/.test(label)) return;
        if (button.type !== 'submit' && !/^(log\s?in|sign\s?in|continue|verify|登录|登陆|验证并登录)$/.test(label)) return;
        capture(button.form || button.closest('form,[role="dialog"]') || document);
      }, true);
      document.addEventListener('keydown', event => {
        if (event.isTrusted && event.key === 'Enter' && !event.isComposing &&
          event.target instanceof HTMLInputElement && (event.target.type === 'password' || isCode(event.target))) {
          capture(event.target.form || event.target.closest('[role="dialog"]') || document);
        }
      }, true);
    })();
""".trimIndent()

internal fun credentialFillScript(login: BrowserLogin): String = """
    (() => {
      if (window !== window.top || location.origin !== new URL(${JsonPrimitive(login.origin)}).origin) return 'wrong_origin';
      $credentialFieldHelpers
      const visible = el => !el.disabled && !el.readOnly && el.getClientRects().length > 0;
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      const fill = (el, value) => {
        setter.call(el, value);
        el.dispatchEvent(new Event('input', {bubbles:true}));
        el.dispatchEvent(new Event('change', {bubbles:true}));
      };
      if (${login.kind == BrowserLoginKind.SMS}) {
        const phones = Array.from(document.querySelectorAll('input')).filter(el => visible(el) && isPhone(el));
        if (phones.length !== 1) return 'no_form';
        const form = phones[0].form;
        if (form && new URL(form.action || location.href, location.href).origin !== location.origin) return 'wrong_origin';
        fill(phones[0], ${JsonPrimitive(login.username)});
        return 'filled';
      }
      const passwords = Array.from(document.querySelectorAll('input[type="password"]'))
        .filter(el => visible(el) && !isCode(el) && el.autocomplete !== 'new-password');
      if (passwords.length !== 1) return 'no_form';
      const password = passwords[0];
      const form = password.form;
      if (!form || new URL(form.action || location.href, location.href).origin !== location.origin) return 'wrong_origin';
      const fields = Array.from(form.elements).filter(el => el instanceof HTMLInputElement && visible(el));
      const candidates = fields.filter(el => ['text', 'email', 'tel'].includes(el.type) && !isCode(el));
      const username = candidates.find(el => el.autocomplete === 'username') ||
        candidates.find(el => el.type === 'email' || /user|login|email|account/i.test(el.name + ' ' + el.id)) ||
        candidates.filter(el => fields.indexOf(el) < fields.indexOf(password)).pop();
      if (username) fill(username, ${JsonPrimitive(login.username)});
      fill(password, ${JsonPrimitive(login.password)});
      return 'filled';
    })();
""".trimIndent()
