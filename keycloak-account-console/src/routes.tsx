import type { RouteObject } from "react-router-dom";
import { Navigate } from "react-router-dom";
import App from "./App";
import { environment } from "./environment";
import { SecurityHome } from "./SecurityHome";

export const RootRoute: RouteObject = {
  path: decodeURIComponent(new URL(environment.baseUrl).pathname),
  element: <App />,
  errorElement: <>Error</>,
  children: [
    { index: true, element: <SecurityHome /> },
    { path: "security", element: <SecurityHome /> },
    { path: "*", element: <Navigate to="." replace /> },
  ],
};

export const routes: RouteObject[] = [RootRoute];
