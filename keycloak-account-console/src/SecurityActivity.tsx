import {
  type AccountEnvironment,
  type DeviceRepresentation,
  type SessionRepresentation,
  deleteSession,
  getDevices,
  useEnvironment,
} from "@keycloak/keycloak-account-ui";
import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import style from "./SecurityActivity.module.css";

type SessionItem = {
  device: DeviceRepresentation;
  session: SessionRepresentation;
};

export const SecurityActivity = () => {
  const { i18n, t } = useTranslation();
  const context = useEnvironment<AccountEnvironment>();
  const [devices, setDevices] = useState<DeviceRepresentation[]>();
  const [refreshKey, setRefreshKey] = useState(0);
  const [pendingSignOut, setPendingSignOut] = useState<SessionItem>();
  const [isSigningOut, setIsSigningOut] = useState(false);
  const [error, setError] = useState<string>();
  const [notice, setNotice] = useState<string>();

  useEffect(() => {
    const controller = new AbortController();
    setError(undefined);

    getDevices({ signal: controller.signal, context })
      .then(setDevices)
      .catch((loadError: unknown) => {
        if (!controller.signal.aborted) {
          console.error("Unable to load signed-in devices", loadError);
          setError(
            t("activityLoadError", {
              defaultValue: "Không thể tải hoạt động đăng nhập. Vui lòng thử lại.",
            }),
          );
        }
      });

    return () => controller.abort();
  }, [context, refreshKey, t]);

  const sessions = useMemo<SessionItem[]>(
    () =>
      (devices ?? [])
        .flatMap((device) =>
          device.sessions.map((session) => ({ device, session })),
        )
        .sort((left, right) => {
          if (left.session.current !== right.session.current) {
            return left.session.current ? -1 : 1;
          }
          return right.session.lastAccess - left.session.lastAccess;
        }),
    [devices],
  );

  const formatTime = (timestamp: number) =>
    new Intl.DateTimeFormat(i18n.resolvedLanguage ?? i18n.language, {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(new Date(timestamp * 1000));

  const deviceTitle = ({ device, session }: SessionItem) => {
    const unknownOs = device.os.toLowerCase().includes("unknown");
    const unknownVersion = device.osVersion.toLowerCase().includes("unknown");
    const os = unknownOs
      ? t("unknownDevice", { defaultValue: "Thiết bị không xác định" })
      : `${device.os}${unknownVersion ? "" : ` ${device.osVersion}`}`;
    const browser = session.browser ||
      t("unknownBrowser", { defaultValue: "Trình duyệt không xác định" });

    return `${os} · ${browser}`;
  };

  const clientNames = (session: SessionRepresentation) => {
    const names = session.clients
      .map((client) => {
        const translatedKey = client.clientName?.match(/^\$\{(.+)\}$/)?.[1];
        return translatedKey
          ? t(translatedKey, { defaultValue: client.clientId })
          : client.clientName || client.clientId;
      })
      .filter(Boolean);

    return names.length
      ? names.join(", ")
      : t("unknownClient", { defaultValue: "Không xác định" });
  };

  const refresh = () => {
    setDevices(undefined);
    setNotice(undefined);
    setRefreshKey((key) => key + 1);
  };

  const confirmSignOut = async () => {
    if (!pendingSignOut) return;

    setIsSigningOut(true);
    setError(undefined);
    try {
      const response = await deleteSession(context, pendingSignOut.session.id);
      if (!response.ok) {
        throw new Error(`Keycloak returned ${response.status}`);
      }

      setPendingSignOut(undefined);
      setNotice(
        t("sessionSignedOut", {
          defaultValue: "Đã đăng xuất phiên trên thiết bị đã chọn.",
        }),
      );
      setRefreshKey((key) => key + 1);
    } catch (signOutError) {
      console.error("Unable to sign out session", signOutError);
      setError(
        t("sessionSignOutError", {
          defaultValue: "Không thể đăng xuất phiên này. Vui lòng thử lại.",
        }),
      );
    } finally {
      setIsSigningOut(false);
    }
  };

  return (
    <section className={style.activity} aria-labelledby="security-activity-title">
      <div className={style.sectionHeader}>
        <div>
          <div className={style.eyebrow}>
            {t("activityEyebrow", { defaultValue: "Security Activity" })}
          </div>
          <h2 id="security-activity-title">
            {t("activityTitle", { defaultValue: "Hoạt động bảo mật" })}
          </h2>
          <p>
            {t("activitySubtitle", {
              defaultValue:
                "Theo dõi các thiết bị và phiên đang đăng nhập vào tài khoản của bạn.",
            })}
          </p>
        </div>
        <button className={style.refreshButton} type="button" onClick={refresh}>
          <span aria-hidden="true">↻</span>
          {t("refreshActivity", { defaultValue: "Làm mới" })}
        </button>
      </div>

      {notice && <div className={style.notice} role="status">{notice}</div>}
      {error && <div className={style.error} role="alert">{error}</div>}

      {!devices && !error && (
        <div className={style.loading} role="status">
          <span />
          {t("activityLoading", { defaultValue: "Đang tải hoạt động đăng nhập..." })}
        </div>
      )}

      {devices && sessions.length === 0 && (
        <div className={style.empty}>
          {t("activityEmpty", { defaultValue: "Không có phiên đăng nhập nào." })}
        </div>
      )}

      <div className={style.sessionList}>
        {sessions.map((item) => {
          const { device, session } = item;
          return (
            <article className={style.sessionCard} key={session.id}>
              <div className={style.deviceIcon} aria-hidden="true">
                {device.mobile ? (
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <rect x="6" y="2" width="12" height="20" rx="3" />
                    <path d="M10 18h4" />
                  </svg>
                ) : (
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <rect x="2" y="3" width="20" height="14" rx="2" />
                    <path d="M8 21h8M12 17v4" />
                  </svg>
                )}
              </div>

              <div className={style.sessionBody}>
                <div className={style.sessionHeading}>
                  <div>
                    <h3>{deviceTitle(item)}</h3>
                    <span className={style.deviceType}>
                      {device.device &&
                      !device.device.toLowerCase().includes("unknown") &&
                      device.device.toLowerCase() !== "other"
                        ? device.device
                        : device.mobile
                          ? t("mobileDevice", { defaultValue: "Thiết bị di động" })
                          : t("desktopDevice", { defaultValue: "Máy tính" })}
                    </span>
                  </div>
                  {session.current ? (
                    <span className={style.currentBadge}>
                      {t("currentSession", { defaultValue: "Phiên hiện tại" })}
                    </span>
                  ) : (
                    <button
                      className={style.signOutButton}
                      type="button"
                      onClick={() => setPendingSignOut(item)}
                    >
                      {t("signOutSession", { defaultValue: "Đăng xuất" })}
                    </button>
                  )}
                </div>

                <dl className={style.sessionDetails}>
                  <div>
                    <dt>{t("ipAddress", { defaultValue: "Địa chỉ IP" })}</dt>
                    <dd>{session.ipAddress || device.ipAddress}</dd>
                  </div>
                  <div>
                    <dt>{t("lastAccess", { defaultValue: "Truy cập gần nhất" })}</dt>
                    <dd>{formatTime(session.lastAccess)}</dd>
                  </div>
                  <div>
                    <dt>{t("sessionStarted", { defaultValue: "Bắt đầu" })}</dt>
                    <dd>{formatTime(session.started)}</dd>
                  </div>
                  <div>
                    <dt>{t("sessionExpires", { defaultValue: "Hết hạn" })}</dt>
                    <dd>{formatTime(session.expires)}</dd>
                  </div>
                  <div className={style.clientsDetail}>
                    <dt>{t("signedInApps", { defaultValue: "Ứng dụng" })}</dt>
                    <dd>{clientNames(session)}</dd>
                  </div>
                </dl>
              </div>
            </article>
          );
        })}
      </div>

      {pendingSignOut && (
        <div className={style.modalBackdrop} role="presentation">
          <div
            className={style.modal}
            role="dialog"
            aria-modal="true"
            aria-labelledby="sign-out-session-title"
          >
            <div className={style.modalIcon} aria-hidden="true">!</div>
            <h3 id="sign-out-session-title">
              {t("confirmSignOutTitle", { defaultValue: "Đăng xuất thiết bị này?" })}
            </h3>
            <p>
              {t("confirmSignOutDescription", {
                defaultValue:
                  "Phiên trên {{device}} sẽ bị kết thúc và cần đăng nhập lại.",
                device: deviceTitle(pendingSignOut),
              })}
            </p>
            <div className={style.modalActions}>
              <button
                className={style.cancelButton}
                type="button"
                disabled={isSigningOut}
                onClick={() => setPendingSignOut(undefined)}
              >
                {t("cancel", { defaultValue: "Hủy" })}
              </button>
              <button
                className={style.confirmButton}
                type="button"
                disabled={isSigningOut}
                onClick={() => void confirmSignOut()}
              >
                {isSigningOut
                  ? t("signingOut", { defaultValue: "Đang đăng xuất..." })
                  : t("confirmSignOut", { defaultValue: "Đăng xuất phiên" })}
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
};
