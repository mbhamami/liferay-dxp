# Liferay DXP (2025.Q3 + 2026.Q1) + Keycloak — Stack Docker Compose

Stack de développement multi-instances : **deux portails Liferay DXP** de versions
différentes, intégrés au **même** Keycloak via OpenID Connect (OIDC).

| Instance   | Version DXP            | Portail                  |
|------------|------------------------|--------------------------|
| `liferay`  | `2025.q3.0`            | <http://localhost:18080> |
| `liferay2` | `2026.q1.0-lts` (LTS)  | <http://localhost:28080> |

Les deux se connectent au **même realm Keycloak** (client `liferay` partagé).

## Sommaire

- [Prérequis](#prérequis)
- [Architecture](#architecture)
- [Lancement de la stack](#lancement-de-la-stack)
- [Clé d'activation DXP](#clé-dactivation-dxp)
- [Configuration de Keycloak](#configuration-de-keycloak)
- [Configuration de Liferay (OpenID Connect)](#configuration-de-liferay-openid-connect)
- [Deuxième instance (2026.Q1)](#deuxième-instance-2026q1)
- [Mapping des claims OIDC → utilisateur Liferay](#mapping-des-claims-oidc--utilisateur-liferay)
- [Module `oidc-claims-logger`](#module-oidc-claims-logger)
- [Module `keycloak-group-mapper`](#module-keycloak-group-mapper)
- [Commandes utiles](#commandes-utiles)
- [Dépannage](#dépannage)

---

## Prérequis

- Docker Desktop (ou Docker Engine + Compose v2)
- ~8 Go de RAM disponibles (deux Liferay réclament ~4 Go chacun)
- Une **clé d'activation Liferay DXP** (`*.li`) — voir [Clé d'activation DXP](#clé-dactivation-dxp).
  Les images `liferay/dxp` démarrent en mode trial sans clé.

> ℹ️ **Images** : `liferay/dxp:2025.q3.0` et `liferay/dxp:2026.q1.0-lts`.
> Tags disponibles : [hub.docker.com/r/liferay/dxp/tags](https://hub.docker.com/r/liferay/dxp/tags).
> `2026.q1` n'existe qu'en variante **LTS** (`-lts`).

---

## Architecture

| Service       | Image                              | Ports hôte                 | Rôle                          |
|---------------|------------------------------------|----------------------------|-------------------------------|
| `liferay`     | `liferay/dxp:2025.q3.0`            | **18080**, 11311, 8000     | Portail DXP 2025.Q3           |
| `liferay-db`  | `postgres:16`                      | —                          | Base `lportal`                |
| `liferay2`    | `liferay/dxp:2026.q1.0-lts`        | **28080**, 21311, 8001     | Portail DXP 2026.Q1 (LTS)     |
| `liferay2-db` | `postgres:16`                      | —                          | Base `lportal2`               |
| `keycloak`    | `quay.io/keycloak/keycloak:26.0`   | **18280**                  | IAM / fournisseur OIDC        |
| `keycloak-db` | `postgres:16`                      | —                          | Base `keycloak`               |

Tous les services partagent le réseau `liferay-net` et s'adressent par nom de
service (ex. `http://keycloak:8080` depuis un conteneur Liferay).

> ⚠️ **Chaque Liferay a sa propre base** (`lportal` / `lportal2`). Deux instances
> ne doivent **jamais** partager la même base.

> ⚠️ **Montée de version** : `LIFERAY_UPGRADE_PERIOD_DATABASE_PERIOD_AUTO_PERIOD_RUN=true`
> est activé sur les deux instances pour que Liferay migre le schéma au démarrage
> (sinon il s'arrête avec *« You must first upgrade the portal to the required
> schema version »*).

### Arborescence

```
.
├── docker-compose.yml
├── README.md
├── keycloak/
│   └── import/
│       └── liferay-realm.json          # Realm + client OIDC + utilisateurs + claim "momo"
├── liferay/                            # Instance 1 (2025.q3.0)
│   ├── deploy/
│   └── osgi/configs/                   # Configs OSGi : OIDC + provider Keycloak
├── liferay2/                           # Instance 2 (2026.q1.0-lts)
│   ├── deploy/                         # (*.li gitignoré)
│   └── osgi/configs/                   # Mêmes configs + customClaims (user_momo)
└── modules/
    ├── oidc-claims-logger/             # Logge le JSON complet des claims + mappe les custom fields (2025.q3)
    └── keycloak-group-mapper/          # Mappe la claim "groups" → User Groups Liferay
```

---

## Lancement de la stack

```powershell
docker compose up -d
```

Suivre le démarrage (premier boot = création de schéma, 3-8 min) :

```powershell
docker compose logs -f liferay      # ou liferay2
```

Attendre `Server startup in [xxx] milliseconds`.

Comptes admin locaux par défaut : `test@liferay.com` / `test` (changement de mot
de passe imposé au 1er login). Sur cette stack le mot de passe a été fixé à `admin`.

---

## Clé d'activation DXP

Les images `liferay/dxp` démarrent en trial. Pour activer une instance, déposer
une clé d'activation DXP (`LiferayActivationKey_*.li`) dans le dossier de licence :

```
/opt/liferay/data/license/<cle>.li
```

Dans cette stack, la clé de l'instance 1 a été copiée vers l'instance 2 :

```powershell
# Copier la clé de l'instance 1 vers l'instance 2 (data/license = volume, lu au démarrage)
docker exec -u root liferay2 sh -lc 'mkdir -p /opt/liferay/data/license'
docker cp <cle>.li liferay2:/opt/liferay/data/license/<cle>.li
docker exec -u root liferay2 sh -lc 'chown -R liferay:liferay /opt/liferay/data/license'
docker compose restart liferay2
```

Au redémarrage, le log doit afficher :

```
DXP Development license validation passed
License registered for DXP Development
```

> 🔒 **Ne committez jamais de `.li`** — `liferay2/.gitignore` exclut déjà `*.li`.

---

## Configuration de Keycloak

Le realm `liferay`, le client OIDC, les utilisateurs et les mappers de claims sont
importés automatiquement depuis
[`keycloak/import/liferay-realm.json`](keycloak/import/liferay-realm.json).

| Élément             | Valeur                                                        |
|---------------------|--------------------------------------------------------------|
| Realm               | `liferay`                                                    |
| Client ID / Secret  | `liferay` / `liferay-secret-change-me`                       |
| Redirect URIs       | `http://localhost:18080/*`, `http://localhost:28080/*`       |
| Web origins         | `http://localhost:18080`, `http://localhost:28080`           |
| Utilisateur n°1     | `alice` / `Passw0rd!` — groupe `editors`, attribut `momo=alice-momo` |
| Utilisateur n°2     | `bob` / `Passw0rd!` — groupe `viewers`, attribut `momo=bob-momo`     |
| Console admin       | <http://localhost:18280> (`admin` / `admin`)                |

> Les **deux** redirect URIs (18080 et 28080) doivent rester déclarés pour que
> les deux instances puissent utiliser le même client.

### Claims exposés sur le client `liferay`

- **`groups`** — `oidc-group-membership-mapper` (liste des groupes).
- **`momo`** — `oidc-usermodel-attribute-mapper` (attribut utilisateur `momo`),
  présent dans l'ID token, l'access token et le UserInfo.

L'attribut `momo` est déclaré comme **attribut géré** du *User Profile* du realm
(visible/éditable dans la console : *Users → \<user\> → onglet Details → champ Momo*).

### Reset complet du realm

L'import est ignoré si le realm existe déjà. Pour repartir de zéro :

```powershell
docker compose down -v
docker compose up -d
```

---

## Configuration de Liferay (OpenID Connect)

Chaque instance monte ses configs OSGi dans `/opt/liferay/osgi/configs/` :

- `…OpenIdConnectConfiguration.config` → active OIDC (`enabled=true`).
- `…OpenIdConnectProviderConfiguration~Keycloak.config` → déclare le fournisseur
  **Keycloak** (Client ID/Secret, *Discovery Endpoint*
  `http://keycloak:8080/realms/liferay/.well-known/openid-configuration`).

> ⚠️ **Liferay consomme ces fichiers.** Au démarrage il importe les `.config`
> dans son ConfigurationAdmin **stocké en base** (table `Configuration_`), puis
> peut **retirer le fichier factory** (`…~Keycloak.config`). La config « vit »
> ensuite en base et est éditable via *Panneau de Contrôle → Paramètres →
> Paramètres système → OpenID Connect*. Sur un bind-mount Windows, le
> hot-reload n'est pas fiable : **redémarrer l'instance** pour réappliquer une
> modification de fichier.

Vérifier le provider : *Panneau de Contrôle → Sécurité → **Administration des
clients OAuth*** → le client `liferay` doit apparaître.

### Connexion via Keycloak

1. Page de connexion Liferay → **Connexion OpenId** → **Keycloak**.
2. Saisir `alice` / `Passw0rd!`.
3. Retour sur Liferay, connecté (compte créé/màj depuis les claims).

---

## Deuxième instance (2026.Q1)

`liferay2` est un portail DXP 2026.q1.0-lts indépendant :

- Port **28080**, base dédiée **`lportal2`**, volume `liferay2-data`.
- Même Keycloak : copie des configs OIDC dans
  [`liferay2/osgi/configs/`](liferay2/osgi/configs), redirect URI `28080` déclaré
  côté Keycloak.
- Clé d'activation déposée (voir [Clé d'activation DXP](#clé-dactivation-dxp)).

> Les custom fields, comptes et configs **ne sont pas partagés** avec l'instance 1
> (base distincte). Tout custom field utilisé dans un mapping doit être recréé ici.

---

## Mapping des claims OIDC → utilisateur Liferay

Trois mécanismes selon la cible, **vérifiés sur le code source réel** de chaque
version :

### 1. Attributs standard du User — « User Info Mapper »

Champ *Mappeur d'informations utilisateur OpenId Connect JSON* (UI : édition du
client OAuth ; ou propriété OSGi). Il ne gère que des **sections fixes** :
`user`, `contact`, `address`, `phone`, `users_roles`, `users_groups`. Notation
imbriquée avec `->` (ex. `address->postal_code`), **pas** de notation pointée ni
de section custom.

```json
{
  "user":  { "emailAddress": "email", "firstName": "given_name", "lastName": "family_name" },
  "contact": { "birthdate": "birthdate", "gender": "gender" },
  "address": { "city": "address->locality", "zip": "address->postal_code" },
  "users_groups": { "groups": "groups" }
}
```

Lecture : `"<champ Liferay>": "<claim OIDC>"`.

### 2. Custom field (Expando) — selon la version

> ⚠️ Le « User Info Mapper » ci-dessus **ne sait pas** écrire dans un custom field.
> Le mécanisme dépend de la version DXP.

#### 2026.Q1+ — propriété `customClaims` du provider

`OIDCUserInfoProcessor` lit un champ **séparé** `customClaimsJSON` de
l'`OAuthClientEntry` (méthode `_addOrUpdateUserCustomClaims`). Cette valeur
**n'est pas saisissable dans l'UI** *Administration des clients OAuth* (l'action
y passe `null` en dur). Elle provient de la propriété `customClaims` du
**provider OpenID Connect**, transformée `clé=valeur` → `{"clé":"valeur"}` par
`OpenIdConnectProviderPortalInstanceLifecycleListener` (au démarrage de l'instance
et à chaque création/màj de la config provider).

➡️ À configurer dans le fichier provider
[`liferay2/osgi/configs/…OpenIdConnectProviderConfiguration~Keycloak.config`](liferay2/osgi/configs/com.liferay.portal.security.sso.openid.connect.internal.configuration.OpenIdConnectProviderConfiguration~Keycloak.config) :

```properties
customClaims=[ \
  "user_momo=momo", \
  ]
```

Format : `"<custom field Liferay>=<claim OIDC>"` (la clé est le custom field,
la valeur est le claim). Devient `OAuthClientEntry.customClaimsJSON =
{"user_momo":"momo"}`.

**Prérequis** : créer le custom field au préalable —
*Panneau de Contrôle → Paramètres → Champs personnalisés → Utilisateur* (clé
`user_momo`, type Text). Après modif du `.config`, **redémarrer l'instance**
(hot-reload non fiable sur bind-mount Windows).

#### 2025.Q3 — pas de support natif → module code

Dans 2025.q3.0, `OIDCUserInfoProcessor` **n'a ni section custom ni
`customClaimsJSON`** (vérifié sur le bytecode déployé). Le mapping vers un custom
field se fait par code : voir
[`OIDCCustomFieldMapperPostLoginAction`](modules/oidc-claims-logger) ci-dessous.

### 3. UserGroups / Rôles / Organizations

Non gérés par le User Info Mapper → module OSGi
[`keycloak-group-mapper`](#module-keycloak-group-mapper).

### Récapitulatif

| Besoin                                  | 2025.Q3                              | 2026.Q1+                                   |
|-----------------------------------------|-------------------------------------|--------------------------------------------|
| email / nom / prénom / adresse / phone  | User Info Mapper (sections fixes)   | User Info Mapper (sections fixes)          |
| Custom field (Expando)                  | Module `oidc-claims-logger`         | Propriété `customClaims` du provider       |
| UserGroups depuis claim `groups`        | Module `keycloak-group-mapper`      | Module `keycloak-group-mapper`             |

> 💡 **Pourquoi ne pas surcharger `OIDCUserInfoProcessor` ?** C'est une classe
> `internal` (`Private-Package`, non exportée) publiée comme service sous son
> propre type concret → ni `extends`, ni override par ranking possibles depuis un
> module externe. La voie supportée est une `LifecycleAction` post-login.

---

## Module `oidc-claims-logger`

[`modules/oidc-claims-logger`](modules/oidc-claims-logger) — deux
`LifecycleAction` sur `login.events.post` :

1. **`OIDCClaimsLoggerPostLoginAction`** — décode l'access token de la session
   OIDC et **logge le JSON complet des claims** reçus à l'authentification.
2. **`OIDCCustomFieldMapperPostLoginAction`** — mappe des claims vers des custom
   fields (par défaut `momo` → `user_momo`) via l'`ExpandoBridge`. C'est
   l'équivalent code de `customClaims` pour les versions sans support natif
   (2025.Q3).

### Build & déploiement

```powershell
.\modules\oidc-claims-logger\build.ps1     # ou build.sh
```

Le script copie les **vrais jars du runtime** depuis le conteneur (`portal-kernel.jar`,
l'API OIDC) dans `libs/`, compile contre eux (bnd dérive ainsi les bonnes plages
de versions), puis dépose le bundle via `docker cp` dans `/opt/liferay/osgi/modules/`.

> ⚠️ **Jakarta** : DXP 2025.Q3+ est passé à `jakarta.servlet` (Servlet 6.0) ;
> `LifecycleEvent.getRequest()` renvoie un `jakarta.servlet.http.HttpServletRequest`.
> Les modules doivent importer `jakarta.servlet`, pas `javax.servlet`.

Vérifier : `docker compose logs liferay | Select-String 'oidc.claims.logger'` →
`STARTED com.example.oidc.claims.logger_1.0.0`. Au login OIDC, le JSON des claims
(dont `momo`) apparaît dans les logs.

---

## Module `keycloak-group-mapper`

[`modules/keycloak-group-mapper`](modules/keycloak-group-mapper) — `LifecycleAction`
post-login qui décode la claim `groups` du JWT et synchronise les **User Groups**
Liferay (création des manquants, assignation, retrait des absents).

> ⚠️ **À migrer** : ce module utilise encore `javax.servlet` et d'anciennes
> versions d'API (`release.portal.api:7.4.3.132`, OIDC api package v7.0). Il **ne
> se résout pas** sur DXP 2025.Q3+ (Jakarta + OIDC package v8.0). Calquer sa
> compilation sur `oidc-claims-logger` (jars runtime + `jakarta.servlet`).

La claim `groups` est déjà exposée par le realm importé. Vérification rapide :

```bash
TOKEN=$(curl -s -X POST \
  "http://localhost:18280/realms/liferay/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" -d "client_id=liferay" \
  -d "client_secret=liferay-secret-change-me" \
  -d "username=alice" -d "password=Passw0rd!" -d "scope=openid" \
  | jq -r .access_token)

echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq '{preferred_username, groups, momo}'
```

Sortie attendue : `{ "preferred_username": "alice", "groups": ["editors"], "momo": "alice-momo" }`.

---

## Commandes utiles

```powershell
docker compose up -d                 # Démarrer
docker compose ps                    # État
docker compose logs -f liferay2      # Logs d'une instance
docker compose stop                  # Arrêter (conserve les données)
docker compose down -v               # Tout supprimer (reset complet)

# Console Gogo OSGi
docker exec -it liferay  sh -lc "telnet localhost 11311"
docker exec -it liferay2 sh -lc "telnet localhost 11311"
```

---

## Dépannage

| Symptôme                                                   | Cause probable / solution                                                                                       |
|------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------|
| *« You must first upgrade the portal… schema version »*     | Montée de version sans migration → `LIFERAY_UPGRADE_PERIOD_DATABASE_PERIOD_AUTO_PERIOD_RUN=true` (déjà activé). |
| Liferay reste sur *Starting Liferay*                        | Mémoire Docker insuffisante (≥ 8 Go pour deux instances).                                                       |
| OIDC *Invalid redirect_uri*                                 | Le client Keycloak doit lister `http://localhost:18080/*` **et** `http://localhost:28080/*`.                    |
| OIDC *Unknown issuer*                                       | Le *Discovery Endpoint* doit pointer sur `http://keycloak:8080/...` (réseau interne), pas `localhost`.          |
| Le fichier `.config` « disparaît »                          | Normal : Liferay le consomme en base. Le réglage vit dans *Paramètres système* ; redémarrer pour réimporter.    |
| `No expando column found with name user_momo`               | Créer le custom field `user_momo` sur l'instance concernée (base distincte par instance).                       |
| Custom field non rempli après login                         | Vérifier `OAuthClientEntry.customClaimsJSON` (2026.Q1) ou le bundle `oidc-claims-logger` ACTIVE (2025.Q3).      |
| Bundle OSGi `Unresolved requirement: Import-Package`        | Versions d'API non alignées sur le runtime (Jakarta / OIDC v8). Compiler contre les jars du conteneur.          |
| Licence non activée                                         | Déposer le `.li` dans `data/license/` puis redémarrer ; vérifier `License registered` dans les logs.           |

---

## Références

- Documentation Liferay : <https://learn.liferay.com/>
- OpenID Connect Liferay : <https://learn.liferay.com/web/guest/w/dxp/system-administration/authentication/openid-connect>
- Image Docker Liferay DXP : <https://hub.docker.com/r/liferay/dxp>
- Source publique (tag le plus proche) : <https://github.com/liferay/liferay-portal/tree/2026.q1.0>
- Documentation Keycloak : <https://www.keycloak.org/documentation>
