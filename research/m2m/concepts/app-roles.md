# App roles

An **App Role** is a custom permission you define yourself on an Entra ID
app registration's manifest, under the `appRoles` array. It's just:

- a `value` string — e.g. `SLO.Read` — this is the literal string
  your own API code checks for
- a `displayName` / `description` for humans reading the Azure Portal
- an [`allowedMemberTypes`](./member-types.md) list
- an `id` (a GUID you generate)

When some principal (a user, a group, or another application) is
**assigned** that role on this app's Enterprise Application (its service
principal object), any access token issued for this app and that principal
will carry the role's `value` inside the token's `roles` claim. Your API
then decides what each role value is allowed to do — here,
`src/main/kotlin/no/bekk/routes/M2mSloRouting.kt` checks:

```kotlin
val roles = principal?.payload?.getClaim("roles")?.asList(String::class.java) ?: emptyList()
if ("SLO.Read" !in roles) { /* 403 */ }
```

It's entirely self-defined, fine-grained authorization — independent of
Entra's built-in directory roles (Global Administrator, etc.). Think of it
as "a permission your own app invented, that Entra just carries around
inside tokens for you."

Reference: [Add app roles to your application](https://learn.microsoft.com/en-us/entra/identity-platform/howto-add-app-roles-in-apps).

See also: [member types](./member-types.md), [client credentials flow](./client-credentials-flow.md).
