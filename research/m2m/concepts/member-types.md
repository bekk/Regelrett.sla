# Member types (`allowedMemberTypes`)

Every [app role](./app-roles.md) declares *what kind of principal* it's
allowed to be assigned to:

- **`Users/Groups`** — only human users or groups of users. This is the
  classic "show this role in the token when a human with this role signs
  in" pattern (delegated tokens, the kind a browser login produces).
- **`Application`** — only *other applications' service principals*. This
  is what makes the role show up in an **app-only** token obtained via the
  [client credentials flow](./client-credentials-flow.md), since there's no
  user involved at all in that flow — the "member" being granted the role
  is itself an application identity.

You can declare both (`["User", "Application"]`) if a role should work
either way.

## Why this endpoint needs `Application`

The caller for `GET /api/m2m/slo` is a scheduled GitHub Actions job
with no human behind it. If the app role were left at the default
`Users/Groups`, a client-credentials token would simply **never** contain
the role, no matter what was granted in the Portal — the endpoint would
always respond `403`, because `Users/Groups` roles don't get attached to
app-only tokens at all. `Application` is the only member type that makes
this work.

Reference: [Add app roles to your application](https://learn.microsoft.com/en-us/entra/identity-platform/howto-add-app-roles-in-apps).
