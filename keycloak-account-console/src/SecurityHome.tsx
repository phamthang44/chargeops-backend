import {
  type AccountEnvironment,
  type CredentialContainer,
  getCredentials,
  useEnvironment,
} from "@keycloak/keycloak-account-ui";
import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import style from "./SecurityHome.module.css";
import { SecurityActivity } from "./SecurityActivity";

const PASSWORD_ACTION = "UPDATE_PASSWORD";
const TOTP_ACTION = "CONFIGURE_TOTP";

export const SecurityHome = () => {
  const { i18n, t } = useTranslation();
  const context = useEnvironment<AccountEnvironment>();
  const { environment, keycloak } = context;
  const [credentials, setCredentials] = useState<CredentialContainer[]>();
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    const controller = new AbortController();

    getCredentials({ signal: controller.signal, context })
      .then(setCredentials)
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          console.error("Unable to load account credentials", error);
          setLoadError(true);
        }
      });

    return () => controller.abort();
  }, [context]);

  const password = useMemo(
    () => credentials?.find((item) => item.type === "password"),
    [credentials],
  );
  const otp = useMemo(
    () => credentials?.find((item) => item.type === "otp"),
    [credentials],
  );
  const authenticators = otp?.userCredentialMetadatas ?? [];
  const twoFactorEnabled = authenticators.length > 0;

  const startAction = (action: string) => {
    void keycloak.login({
      action,
      locale: i18n.resolvedLanguage ?? i18n.language ?? environment.locale,
    });
  };

  return (
    <section className={style.page}>
      <div className={style.eyebrow}>ChargeOps Security</div>
      <h1>{t("securityTitle", { defaultValue: "Bảo mật tài khoản" })}</h1>
      <p className={style.subtitle}>
        {t("securitySubtitle", {
          defaultValue: "Quản lý các phương thức bạn dùng để đăng nhập ChargeOps.",
        })}
      </p>

      <div className={style.identityNotice}>
        <span aria-hidden="true">i</span>
        {t("identityNotice", {
          defaultValue:
            "Tên đăng nhập và email là thông tin định danh nên không thể thay đổi tại đây.",
        })}
      </div>

      {loadError && (
        <div className={style.error} role="alert">
          {t("securityLoadError", {
            defaultValue: "Không thể tải thông tin bảo mật. Vui lòng thử lại.",
          })}
        </div>
      )}

      <div className={style.cardGrid} aria-busy={!credentials && !loadError}>
        <article className={style.card}>
          <div className={`${style.icon} ${style.passwordIcon}`} aria-hidden="true">
            <span />
          </div>
          <div className={style.cardBody}>
            <div className={style.cardHeading}>
              <h2>{t("passwordTitle", { defaultValue: "Mật khẩu" })}</h2>
              <span className={style.neutralBadge}>
                {t("passwordProtected", { defaultValue: "Đang bảo vệ" })}
              </span>
            </div>
            <p>
              {t("passwordDescription", {
                defaultValue:
                  "Chọn mật khẩu mạnh và riêng biệt cho tài khoản ChargeOps.",
              })}
            </p>
            <button
              className={style.primaryButton}
              type="button"
              disabled={!credentials || loadError}
              onClick={() =>
                startAction(password?.updateAction || PASSWORD_ACTION)
              }
            >
              {t("passwordAction", { defaultValue: "Đổi mật khẩu" })}
              <span aria-hidden="true">→</span>
            </button>
          </div>
        </article>

        <article className={style.card}>
          <div className={`${style.icon} ${style.shieldIcon}`} aria-hidden="true">
            <span>✓</span>
          </div>
          <div className={style.cardBody}>
            <div className={style.cardHeading}>
              <h2>
                {t("twoFactorTitle", { defaultValue: "Xác thực hai bước" })}
              </h2>
              {credentials && (
                <span
                  className={
                    twoFactorEnabled ? style.enabledBadge : style.disabledBadge
                  }
                >
                  {twoFactorEnabled
                    ? t("twoFactorEnabled", { defaultValue: "Đã bật" })
                    : t("twoFactorDisabled", { defaultValue: "Chưa bật" })}
                </span>
              )}
            </div>
            <p>
              {t("twoFactorDescription", {
                defaultValue:
                  "Thêm mã xác minh để bảo vệ tài khoản ngay cả khi mật khẩu bị lộ.",
              })}
            </p>

            {twoFactorEnabled && (
              <div className={style.authenticatorList}>
                {authenticators.map(({ credential }, index) => (
                  <div className={style.authenticator} key={credential.id}>
                    <div>
                      <strong>
                        {credential.userLabel ||
                          t("authenticatorFallback", {
                            defaultValue: "Ứng dụng xác thực {{number}}",
                            number: index + 1,
                          })}
                      </strong>
                      <small>
                        {t("authenticatorActive", { defaultValue: "Đang hoạt động" })}
                      </small>
                    </div>
                    <button
                      className={style.removeButton}
                      type="button"
                      onClick={() =>
                        startAction(`delete_credential:${credential.id}`)
                      }
                    >
                      {t("removeAuthenticator", { defaultValue: "Gỡ" })}
                    </button>
                  </div>
                ))}
              </div>
            )}

            <button
              className={twoFactorEnabled ? style.secondaryButton : style.primaryButton}
              type="button"
              disabled={!credentials || loadError}
              onClick={() => startAction(otp?.createAction || TOTP_ACTION)}
            >
              {twoFactorEnabled
                ? t("addAuthenticator", { defaultValue: "Thêm ứng dụng xác thực" })
                : t("twoFactorAction", { defaultValue: "Bật 2FA" })}
              <span aria-hidden="true">→</span>
            </button>
          </div>
        </article>
      </div>

      <SecurityActivity />

      {environment.referrerUrl && (
        <a className={style.backLink} href={environment.referrerUrl}>
          ← {t("backToApp", { defaultValue: "Quay lại ChargeOps" })}
        </a>
      )}
    </section>
  );
};
