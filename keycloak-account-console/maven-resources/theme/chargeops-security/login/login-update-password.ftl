<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false displayInfo=false; section>
  <#if section == "header">
    <div class="co-header">
      <div class="co-header-btn-space"></div>
      <h1 class="co-header-title">${msg("updatePasswordHeaderTitle")}</h1>
      <div class="co-header-btn-space"></div>
    </div>
  <#elseif section == "form">
    <div class="co-screen-body">
      <h2 class="co-title">${msg("updatePasswordTitle")}</h2>
      <p class="co-subtitle">${msg("updatePasswordSubtitle")}</p>

      <#if message?has_content>
        <div class="co-alert co-alert-${message.type}">
          <span><#if message.type == "warning">${msg("updatePasswordMessage")}<#else>${kcSanitize(message.summary)?no_esc}</#if></span>
        </div>
      </#if>

      <form id="kc-passwd-update-form" class="co-form" action="${url.loginAction}" method="post">
        <input type="text" id="username" name="username" value="${(username!'')}" autocomplete="username" readonly="readonly" hidden />
        <input type="password" id="password" name="password" autocomplete="current-password" hidden />

        <div class="co-input-group">
          <label class="co-input-label" for="password-new">${msg("passwordNew")}</label>
          <div class="co-input-field <#if messagesPerField.existsError('password','password-confirm')>is-error</#if>">
            <span class="co-input-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
            </span>
            <input id="password-new" name="password-new" type="password" class="co-input" placeholder="${msg('registerPasswordPlaceholder')}" autocomplete="new-password" autofocus aria-invalid="<#if messagesPerField.existsError('password','password-confirm')>true</#if>" />
            <button type="button" class="co-password-toggle" onclick="togglePasswordVisibility('password-new', this)" aria-label="${msg('showPassword')}">
              <svg class="co-icon-eye-off" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
              <svg class="co-icon-eye-open" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
            </button>
          </div>
          <#if messagesPerField.existsError('password')>
            <span class="co-field-error">${kcSanitize(messagesPerField.get('password'))?no_esc}</span>
          </#if>
        </div>

        <div class="co-input-group">
          <label class="co-input-label" for="password-confirm">${msg("passwordConfirm")}</label>
          <div class="co-input-field <#if messagesPerField.existsError('password-confirm')>is-error</#if>">
            <span class="co-input-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
            </span>
            <input id="password-confirm" name="password-confirm" type="password" class="co-input" placeholder="${msg('registerConfirmPlaceholder')}" autocomplete="new-password" aria-invalid="<#if messagesPerField.existsError('password-confirm')>true</#if>" />
            <button type="button" class="co-password-toggle" onclick="togglePasswordVisibility('password-confirm', this)" aria-label="${msg('showPassword')}">
              <svg class="co-icon-eye-off" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
              <svg class="co-icon-eye-open" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
            </button>
          </div>
          <#if messagesPerField.existsError('password-confirm')>
            <span class="co-field-error">${kcSanitize(messagesPerField.get('password-confirm'))?no_esc}</span>
          </#if>
        </div>

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
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" class="co-security-icon"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><polyline points="9 12 11 14 15 10"/></svg>
        <span class="co-security-text">${msg("updatePasswordSecurityNote")}</span>
      </div>
    </div>

    <div class="co-screen-footer">
      <button class="co-cta-btn" type="submit" form="kc-passwd-update-form">${msg("doSubmit")}</button>
      <#if isAppInitiatedAction??>
        <button class="co-secondary-btn" type="submit" name="cancel-aia" value="true" form="kc-passwd-update-form">${msg("doCancel")}</button>
      </#if>
    </div>

    <script>
      function togglePasswordVisibility(id, btn) {
        var input = document.getElementById(id);
        if (input.type === 'password') {
          input.type = 'text';
          btn.classList.add('is-visible');
        } else {
          input.type = 'password';
          btn.classList.remove('is-visible');
        }
      }
    </script>
  </#if>
</@layout.registrationLayout>
