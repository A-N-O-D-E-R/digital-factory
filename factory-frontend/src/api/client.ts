import ky, { HTTPError } from "ky";
import type { components } from "./schema";

export type Schemas = components["schemas"];
export type WorkOrder = Schemas["WorkOrderView"];
export type Detail = Schemas["Detail"];
export type Run = Schemas["RunView"];
export type Approval = Schemas["ApprovalView"];
export type CurrentUser = Schemas["CurrentUser"];
export type TimelineEvent = Schemas["Event"];

/** Where an expired or missing session goes: the backend's login (OIDC, session cookie). */
export const LOGIN_PATH = import.meta.env.VITE_LOGIN_PATH ?? "/oauth2/authorization/oidc";

function cookie(name: string): string | undefined {
  return document.cookie.split("; ").find((c) => c.startsWith(name + "="))?.slice(name.length + 1);
}

/**
 * The API, with the session cookie. Changes carry the CSRF token the backend put in the XSRF-TOKEN
 * cookie; a 401 sends the browser to the login page.
 */
export const api = ky.create({
  baseUrl: window.location.origin, // same origin as the page (the backend behind the same host)
  prefix: "/api",
  credentials: "include",
  retry: 0,
  hooks: {
    beforeRequest: [
      ({ request }) => {
        const token = cookie("XSRF-TOKEN");
        if (request.method !== "GET" && token) {
          request.headers.set("X-XSRF-TOKEN", decodeURIComponent(token));
        }
      },
    ],
    afterResponse: [
      ({ response }) => {
        if (response.status === 401) {
          window.location.assign("/login");
        }
      },
    ],
  },
});

/** The message of a failed call: the ProblemDetail's detail when there is one. */
export async function errorMessage(error: unknown): Promise<string> {
  if (error instanceof HTTPError) {
    const body = await error.response.clone().json().catch(() => null);
    return body?.detail ?? body?.message ?? `${error.response.status} ${error.response.statusText}`;
  }
  return error instanceof Error ? error.message : String(error);
}
