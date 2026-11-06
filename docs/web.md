# Web layer, SPA and OIDC (Group 6)

## Static layout

`src/main/webapp/**` moved to `src/main/resources/META-INF/resources/` (Quarkus serves it at `/`):

```
META-INF/resources/index.html          (was index.jsp)
META-INF/resources/app/**              AngularJS 1.x application
META-INF/resources/partials/**         route templates
META-INF/resources/bower_components/** vendored libraries (kept as-is; EOL risk accepted)
```

`index.jsp` only did `request.getSession(true)`; the static `index.html` is identical minus the
scriptlet. Quarkus has no JSP support. `health.jsp` is replaced by SmallRye Health:
`/q/health`, `/q/health/live`, `/q/health/ready`, `/q/health/started`.
`WEB-INF/web.xml` (with `<distributable/>`) and `WEB-INF/beans.xml` are deleted; clustering semantics
now come from the platform (2+ replicas, competing consumers), not the servlet spec.

## Runtime configuration documents

`/coolstore.json` and `/keycloak.json` are still the URLs the SPA fetches, but they are generated at
runtime (no static file, no `localhost` literal):

* `ConfigEndpoint` (`/services/config/…`) builds both documents from config;
* `SpaConfigRoutes` registers Vert.x routes at the original paths so `app/app.js` and
  `new Keycloak('keycloak.json')` work unchanged.

| Document | Fields | Source |
|---|---|---|
| `coolstore.json` | `MONOLITH`, `API_ENDPOINT`, `SECURE_API_ENDPOINT`, `SSO_ENABLED` | `COOLSTORE_MONOLITH`, `API_ENDPOINT`, `SECURE_API_ENDPOINT`, `SSO_ENABLED` |
| `keycloak.json` | `realm`, `auth-server-url`, `resource`, `public-client`, `ssl-required`, `confidential-port` | `OIDC_REALM`, `OIDC_AUTH_SERVER_URL`, `OIDC_CLIENT_ID`, `OIDC_PUBLIC_CLIENT` |

## REST scopes

`CartEndpoint` was `@SessionScoped` (invalid for a Quarkus REST resource) and is now
`@ApplicationScoped @Blocking`; `ProductEndpoint`/`OrderEndpoint` are `@ApplicationScoped @Blocking`
(they do blocking JPA work). All paths, media types and method signatures are unchanged — verified by
`RestContractTest` (9 endpoint methods).

## OIDC

* Realm `eap`, client `eap-app`, `realm-export.json` remains the reference import (owned by the
  identity team; do not edit without coordination).
* The SPA keeps enforcing login (`SSO_ENABLED`).
* Backend protection of `/services/**` is **off by default** (`OIDC_ENABLED=false`): enabling
  `quarkus-oidc` changes behaviour for API consumers and needs sign-off plus a token flow.
* Redirect URIs must include the OpenShift Route hostname (no `localhost`).
