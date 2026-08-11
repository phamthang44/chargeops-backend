import {
  type AccountEnvironment,
  useEnvironment,
} from "@keycloak/keycloak-account-ui";
import { useTranslation } from "react-i18next";
import { Outlet } from "react-router-dom";
import style from "./App.module.css";

function App() {
  const { t } = useTranslation();
  const { environment, keycloak } = useEnvironment<AccountEnvironment>();
  const displayName =
    keycloak.tokenParsed?.name ??
    keycloak.tokenParsed?.preferred_username ??
    "ChargeOps user";

  return (
    <div className={style.appShell}>
      <header className={style.header}>
        <a className={style.brand} href={environment.baseUrl} aria-label="ChargeOps">
          <span className={style.brandMark} aria-hidden="true">C</span>
          <span>ChargeOps</span>
        </a>
        <div className={style.userArea}>
          <span className={style.userName}>{displayName}</span>
          <button
            className={style.logoutButton}
            type="button"
            onClick={() => void keycloak.logout({ redirectUri: environment.baseUrl })}
          >
            {t("logout", { defaultValue: "Đăng xuất" })}
          </button>
        </div>
      </header>
      <main className={style.main}>
        <Outlet />
      </main>
    </div>
  );
}

export default App;
