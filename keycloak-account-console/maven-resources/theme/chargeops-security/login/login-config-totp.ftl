<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false displayInfo=false; section>
  <#if section == "header">
    <div class="co-header">
      <div class="co-header-btn-space"></div>
      <h1 class="co-header-title">${msg("totpHeaderTitle")}</h1>
      <div class="co-header-btn-space"></div>
    </div>
  <#elseif section == "form">
    <div class="co-screen-body co-totp-screen">
      <h2 class="co-title">${msg("totpTitle")}</h2>
      <p class="co-subtitle">${msg("totpSubtitle")}</p>

      <#if message?has_content>
        <div class="co-alert co-alert-${message.type}">
          <span><#if message.type == "warning">${msg("configureTotpMessage")}<#else>${kcSanitize(message.summary)?no_esc}</#if></span>
        </div>
      </#if>

      <form action="${url.loginAction}" class="co-form co-totp-form" id="kc-totp-settings-form" method="post">
        <section class="co-totp-step">
          <div class="co-totp-step-heading">
            <span class="co-totp-step-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="5" y="2" width="14" height="20" rx="3"/><path d="M9 18h6"/>
              </svg>
            </span>
            <div>
              <h3>${msg("totpInstallTitle")}</h3>
              <p>${msg("totpInstallText")}</p>
            </div>
          </div>
          <div class="co-totp-apps">
            <#list totp.supportedApplications as app>
              <span>${msg(app)}</span>
            </#list>
          </div>
        </section>

        <section class="co-totp-step">
          <div class="co-totp-step-heading">
            <span class="co-totp-step-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>
                <rect x="3" y="14" width="7" height="7"/><path d="M14 14h3v3h4v4h-7z"/>
              </svg>
            </span>
            <div>
              <#if mode?? && mode == "manual">
                <h3>${msg("totpManualTitle")}</h3>
                <p>${msg("totpManualText")}</p>
              <#else>
                <h3>${msg("totpScanTitle")}</h3>
                <p>${msg("totpScanText")}</p>
              </#if>
            </div>
          </div>

          <#if mode?? && mode == "manual">
            <div class="co-totp-secret" id="kc-totp-secret-key">${totp.totpSecretEncoded}</div>
            <dl class="co-totp-policy">
              <div><dt>${msg("loginTotpType")}</dt><dd>${msg("loginTotp." + totp.policy.type)}</dd></div>
              <div><dt>${msg("loginTotpAlgorithm")}</dt><dd>${totp.policy.getAlgorithmKey()}</dd></div>
              <div><dt>${msg("loginTotpDigits")}</dt><dd>${totp.policy.digits}</dd></div>
              <#if totp.policy.type == "totp">
                <div><dt>${msg("loginTotpInterval")}</dt><dd>${totp.policy.period}</dd></div>
              <#elseif totp.policy.type == "hotp">
                <div><dt>${msg("loginTotpCounter")}</dt><dd>${totp.policy.initialCounter}</dd></div>
              </#if>
            </dl>
            <a class="co-link co-totp-mode-link" href="${totp.qrUrl}" id="mode-barcode">${msg("totpShowQr")}</a>
          <#else>
            <div class="co-totp-qr-wrap">
              <img id="kc-totp-secret-qr-code" class="co-totp-qr" src="data:image/png;base64, ${totp.totpSecretQrCode}" alt="${msg('totpQrAlt')}" />
            </div>
            <a class="co-link co-totp-mode-link" href="${totp.manualUrl}" id="mode-manual">${msg("totpUnableToScan")}</a>
          </#if>
        </section>

        <section class="co-totp-step">
          <div class="co-totp-step-heading">
            <span class="co-totp-step-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><polyline points="9 12 11 14 15 10"/>
              </svg>
            </span>
            <div>
              <h3>${msg("totpVerifyTitle")}</h3>
              <p>${msg("totpVerifyText")}</p>
            </div>
          </div>

          <div class="co-input-group">
            <label class="co-input-label" for="totp">${msg("totpCodeLabel")}</label>
            <div class="co-input-field <#if messagesPerField.existsError('totp')>is-error</#if>">
              <span class="co-input-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="9"/><path d="M8 12h8M12 8v8"/>
                </svg>
              </span>
              <input type="text" id="totp" name="totp" class="co-input" inputmode="numeric" pattern="[0-9]*" autocomplete="one-time-code" placeholder="${msg('totpCodePlaceholder')}" autofocus aria-invalid="<#if messagesPerField.existsError('totp')>true</#if>" dir="ltr" />
            </div>
            <#if messagesPerField.existsError('totp')>
              <span id="input-error-otp-code" class="co-field-error" aria-live="polite">${kcSanitize(messagesPerField.get('totp'))?no_esc}</span>
            </#if>
          </div>

          <div class="co-input-group">
            <label class="co-input-label" for="userLabel">${msg("totpDeviceLabel")}</label>
            <div class="co-input-field <#if messagesPerField.existsError('userLabel')>is-error</#if>">
              <span class="co-input-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="5" y="2" width="14" height="20" rx="3"/><path d="M9 18h6"/>
                </svg>
              </span>
              <input type="text" id="userLabel" name="userLabel" class="co-input" autocomplete="off" placeholder="${msg('totpDevicePlaceholder')}" aria-invalid="<#if messagesPerField.existsError('userLabel')>true</#if>" />
            </div>
            <#if messagesPerField.existsError('userLabel')>
              <span id="input-error-otp-label" class="co-field-error" aria-live="polite">${kcSanitize(messagesPerField.get('userLabel'))?no_esc}</span>
            </#if>
          </div>
        </section>

        <input type="hidden" id="totpSecret" name="totpSecret" value="${totp.totpSecret}" />
        <#if mode??><input type="hidden" id="mode" name="mode" value="${mode}" /></#if>

        <#if isAppInitiatedAction??>
          <label class="co-checkbox-label co-remember-label">
            <input id="logout-sessions" name="logout-sessions" value="on" type="checkbox" class="co-checkbox-input" checked />
            <span class="co-checkbox-custom">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>
            </span>
            <span class="co-checkbox-text">${msg("logoutOtherSessions")}</span>
          </label>
        </#if>
      </form>

      <div class="co-security-badge">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" class="co-security-icon">
          <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><polyline points="9 12 11 14 15 10"/>
        </svg>
        <span class="co-security-text">${msg("totpSecurityNote")}</span>
      </div>
    </div>

    <div class="co-screen-footer co-totp-footer">
      <button class="co-cta-btn" type="submit" form="kc-totp-settings-form" id="saveTOTPBtn">${msg("totpEnable")}</button>
      <#if isAppInitiatedAction??>
        <button class="co-secondary-btn" type="submit" name="cancel-aia" value="true" form="kc-totp-settings-form" id="cancelTOTPBtn">${msg("doCancel")}</button>
      </#if>
    </div>
  </#if>
</@layout.registrationLayout>
