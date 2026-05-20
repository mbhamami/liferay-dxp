# Liferay Portal CE 7.4 + Keycloak — Stack Docker Compose

Stack de développement pour Liferay Portal CE (Community Edition) intégré à Keycloak via OpenID Connect (OIDC).

> 📝 **Note** : initialement prévue pour Liferay DXP 2026.Q1, cette stack utilise l'édition **CE** (image publique, sans abonnement). Les modules OpenID Connect et les configurations OSGi sont identiques entre CE et DXP — la procédure ci-dessous reste applicable si tu remplaces l'image par `liferay/dxp:2026.q1.7-lts` après `docker login`.

## Sommaire

- [Prérequis](#prérequis)
- [Architecture](#architecture)
- [Lancement de la stack](#lancement-de-la-stack)
- [Premier démarrage de Liferay](#premier-démarrage-de-liferay)
- [Configuration de Keycloak](#configuration-de-keycloak)
- [Configuration de Liferay (OpenID Connect)](#configuration-de-liferay-openid-connect)
- [Connexion via Keycloak](#connexion-via-keycloak)
- [Commandes utiles](#commandes-utiles)
- [Dépannage](#dépannage)

---

## Prérequis

- Docker Desktop (ou Docker Engine + Compose v2)
- 6 Go de RAM disponibles minimum (Liferay réclame ~4 Go)
- Aucun `docker login` requis : l'image `liferay/portal` est publique.

> ℹ️ **Image Liferay CE** : `liferay/portal:7.4.3.132-ga132`. Tags disponibles : [hub.docker.com/r/liferay/portal/tags](https://hub.docker.com/r/liferay/portal/tags).

---

## Architecture

| Service       | Image                              | Port hôte | Rôle                                  |
|---------------|------------------------------------|-----------|---------------------------------------|
| `liferay`     | `liferay/portal:7.4.3.132-ga132`   | **18080** | Portail Liferay CE                    |
| `liferay-db`  | `postgres:16`                      | —         | Base `lportal` pour Liferay           |
| `keycloak`    | `quay.io/keycloak/keycloak:26.0`   | **18280** | IAM / fournisseur OIDC                |
| `keycloak-db` | `postgres:16`                      | —         | Base `keycloak`                       |

Tous les services partagent le réseau `liferay-net` ; ils s'adressent entre eux par nom de service (ex. `http://keycloak:8080` depuis le conteneur Liferay).

### Arborescence

```
.
├── docker-compose.yml
├── README.md
├── keycloak/
│   └── import/
│       └── liferay-realm.json          # Realm + client OIDC + utilisateurs (auto-importés)
├── liferay/
│   ├── deploy/                          # JAR/WAR hot-deployés (cible du build du module)
│   ├── files/                           # Patch / portal-ext.properties éventuels
│   └── osgi/
│       └── configs/                     # Configurations OSGi pré-provisionnées
│           ├── …OpenIdConnectConfiguration.config
│           └── …OpenIdConnectProviderConfiguration~Keycloak.config
└── modules/
    └── keycloak-group-mapper/           # Module OSGi : mapping claims Keycloak → User Groups Liferay
        ├── build.gradle / bnd.bnd
        ├── build.sh / build.ps1         # Build via container gradle:8.5-jdk11
        └── src/main/java/...
```

---

## Lancement de la stack

Depuis la racine du projet :

```powershell
docker compose up -d
```

Suivez le démarrage de Liferay (le premier boot crée le schéma — comptez 3 à 8 minutes) :

```powershell
docker compose logs -f liferay
```

Attendez la ligne :

```
Server startup in [xxx] milliseconds
```

---

## Premier démarrage de Liferay

1. Ouvrez [http://localhost:18080](http://localhost:18080).
2. Connectez-vous avec le compte admin par défaut :
   - Identifiant : `test@liferay.com`
   - Mot de passe : `test`
3. Liferay demande la création d'un nouveau mot de passe et d'une question de sécurité.
4. Acceptez les CGU.

---

## Configuration de Keycloak

### Pré-provisionnement automatique

Le realm `liferay`, le client OIDC `liferay` et deux utilisateurs de test sont
importés automatiquement au premier démarrage à partir de
[`keycloak/import/liferay-realm.json`](keycloak/import/liferay-realm.json).

| Élément             | Valeur                          |
|---------------------|---------------------------------|
| Realm               | `liferay`                       |
| Client ID           | `liferay`                       |
| Client Secret       | `liferay-secret-change-me`      |
| Redirect URIs       | `http://localhost:18080/*`       |
| Utilisateur n°1     | `alice` / `Passw0rd!` — membre du groupe `editors` |
| Utilisateur n°2     | `bob` / `Passw0rd!` — membre du groupe `viewers`   |
| Groupes pré-créés   | `editors`, `viewers`            |
| Console admin       | <http://localhost:18280> (`admin` / `admin`) |

> ⚠️ **Secret à changer en production.** Éditez `keycloak/import/liferay-realm.json`
> *et* `liferay/osgi/configs/com.liferay.portal.security.sso.openid.connect.internal.configuration.OpenIdConnectProviderConfiguration~Keycloak.config`.

### (Optionnel) Modifier le realm via la console

1. Ouvrez <http://localhost:18280> et connectez-vous avec `admin` / `admin`.
2. Sélectionnez le realm **liferay** en haut à gauche.
3. Les modifications faites dans la console ne réécrivent pas le fichier d'import :
   pour persister une nouvelle configuration partageable, exportez le realm et
   remplacez `keycloak/import/liferay-realm.json`.

### Reset complet du realm

L'import est ignoré si le realm existe déjà en base. Pour repartir de zéro :

```powershell
docker compose down -v
docker compose up -d
```

---

## Configuration de Liferay (OpenID Connect)

### Pré-provisionnement automatique

Deux fichiers OSGi sont montés dans `/opt/liferay/osgi/configs/` :

- `com.liferay.portal.security.sso.openid.connect.internal.configuration.OpenIdConnectConfiguration.config`
  → active OpenID Connect (`enabled=true`).
- `com.liferay.portal.security.sso.openid.connect.internal.configuration.OpenIdConnectProviderConfiguration~Keycloak.config`
  → déclare le fournisseur **Keycloak** avec son Client ID / Secret et le
  *Discovery Endpoint* `http://keycloak:8080/realms/liferay/.well-known/openid-configuration`.

Liferay détecte les fichiers `.config` à chaud (ConfigAdmin) : aucun redémarrage
nécessaire après modification. Pour vérifier que la configuration est bien
appliquée :

**Panneau de contrôle → Sécurité → OpenID Connect** → le fournisseur
**Keycloak** doit apparaître dans la liste.

### Ajouter un autre fournisseur OIDC

Dupliquez le fichier `…OpenIdConnectProviderConfiguration~Keycloak.config` en
changeant le suffixe après `~` (ex. `~Azure.config`) et adaptez les valeurs.

### Mapping des attributs utilisateur via le JSON Mapper (UI)

En plus des fichiers OSGi, Liferay propose dans le panneau de contrôle un champ
**"OpenId Connect User Information Mapper JSON"** qui permet de remapper les
claims OIDC vers les attributs utilisateur Liferay sans écrire de code.

> ⚠️ **Portée** : ce JSON gère **uniquement les attributs scalaires** du
> `User` (email, nom, prénom, screenName, jobTitle, attributs custom Expando).
> Pour les **UserGroups / Rôles / Organizations**, il faut le module OSGi
> [`modules/keycloak-group-mapper`](modules/keycloak-group-mapper) décrit plus bas.
> Les deux mécanismes cohabitent sans conflit.

#### Où le configurer

**Panneau de contrôle** → **Security** → **OAuth Client Administration** →
clique sur ton fournisseur **Keycloak** → champ
**"OpenId Connect User Information Mapper JSON"**.

#### Mapping par défaut (équivalent à ce que fait Liferay sans config)

```json
{
  "emailAddress": "email",
  "firstName": "given_name",
  "lastName": "family_name",
  "screenName": "preferred_username"
}
```

Lecture : *« la propriété `emailAddress` du User Liferay est alimentée depuis
la claim `email` du UserInfo Keycloak »*, etc.

#### Exemples utiles

**Ajouter le job title et le numéro de téléphone**

Côté Keycloak, mappe ces attributs via les mappers natifs (`User Attribute` ou
`User Property`) en ajoutant des claims `job_title` et `phone_number`. Puis
côté Liferay :

```json
{
  "emailAddress": "email",
  "firstName": "given_name",
  "lastName": "family_name",
  "screenName": "preferred_username",
  "jobTitle": "job_title"
}
```

**Stocker une claim dans un attribut Expando custom**

Si tu as créé un attribut Expando `companyName` sur l'entité User, mappe une
claim arbitraire :

```json
{
  "emailAddress": "email",
  "firstName": "given_name",
  "lastName": "family_name",
  "expando.companyName": "company"
}
```

**Utiliser une claim imbriquée (notation pointée)**

Si Keycloak renvoie `{ "address": { "locality": "Paris" } }`, tu peux faire :

```json
{
  "emailAddress": "email",
  "city": "address.locality"
}
```

#### Pré-provisionner ce mapping via fichier OSGi

Le mapper JSON est stocké dans la même config factory que le provider. Pour
l'inclure dans le pré-provisionnement (au lieu de le saisir dans l'UI),
ajoute cette ligne dans
[`…OpenIdConnectProviderConfiguration~Keycloak.config`](liferay/osgi/configs/com.liferay.portal.security.sso.openid.connect.internal.configuration.OpenIdConnectProviderConfiguration~Keycloak.config) :

```properties
userInfoMapperJSON="{\"emailAddress\":\"email\",\"firstName\":\"given_name\",\"lastName\":\"family_name\",\"screenName\":\"preferred_username\"}"
```

> Les guillemets internes doivent être échappés (`\"`) car la valeur entière
> est un littéral string OSGi.

#### Tableau récapitulatif : quel outil pour quoi

| Besoin                                                | Outil à utiliser                                   |
|-------------------------------------------------------|----------------------------------------------------|
| Mapper `email`, nom, prénom, screenName, jobTitle      | **JSON Mapper UI** (ou propriété OSGi)             |
| Mapper un attribut Expando custom                      | **JSON Mapper UI** (`expando.xxx`)                 |
| Mapper une claim imbriquée                             | **JSON Mapper UI** (notation pointée)              |
| Créer/assigner un **UserGroup** à partir d'une claim   | Module OSGi `keycloak-group-mapper`                |
| Assigner un **Rôle** à partir d'une claim              | Module OSGi (à adapter, cf. tableau plus bas)      |
| Rattacher à une **Organization**                       | Module OSGi (étendre le service)                   |

---

## Connexion via Keycloak

1. Déconnectez-vous de Liferay (ou ouvrez une fenêtre de navigation privée).
2. Sur la page de connexion, cliquez sur **Sign In with OpenID Connect** → choisissez **Keycloak**.
3. Redirection vers Keycloak → saisissez `alice` / `Passw0rd!`.
4. Vous êtes redirigé sur Liferay, automatiquement loggué (le compte est créé au vol à partir des claims `email`, `given_name`, `family_name`).

---

## Mapping des groupes Keycloak → User Groups Liferay

Module OSGi : [`modules/keycloak-group-mapper`](modules/keycloak-group-mapper).
Implémente un `LifecycleAction` sur l'événement `login.events.post` : à chaque
login OIDC, lit l'`OpenIdConnectSession` de la HTTP session, décode la claim
`groups` du JWT access token, crée les User Groups manquants côté Liferay et
les assigne à l'utilisateur (les groupes absents de la claim sont retirés —
synchronisation).

> 💡 **Pourquoi un `LifecycleAction` et pas `OIDCUserInfoProcessor` ?** Dans
> Liferay 7.4 CE/DXP, `OIDCUserInfoProcessor` est une classe **interne**
> (`com.liferay.portal.security.sso.openid.connect.internal.*`), pas un point
> d'extension public. L'API publique ne propose pas de hook *userinfo*. La voie
> stable et supportée est donc un `LifecycleAction` post-login qui exploite
> `OpenIdConnectSession` (interface publique).

### 1. Exposer la claim `groups` côté Keycloak

La stack expose déjà la claim `groups` automatiquement via le realm pré-importé
([`keycloak/import/liferay-realm.json`](keycloak/import/liferay-realm.json)).
Cette section explique **ce que fait l'import**, et **comment le reproduire à
la main** dans un environnement existant.

#### 1.a — Approche déclarative (déjà appliquée par l'import)

Le realm importé contient :

- Deux groupes : `editors` et `viewers`.
- L'utilisateur `alice` membre de `editors`, `bob` membre de `viewers`.
- Un **protocol mapper** `oidc-group-membership-mapper` rattaché au client
  `liferay`, qui injecte la liste des groupes dans le token sous la clé `groups` :

  ```json
  "protocolMappers": [
    {
      "name": "groups",
      "protocolMapper": "oidc-group-membership-mapper",
      "config": {
        "full.path": "false",
        "id.token.claim": "true",
        "access.token.claim": "true",
        "userinfo.token.claim": "true",
        "claim.name": "groups"
      }
    }
  ]
  ```

> Pour modifier la liste des groupes ou les affectations, édite
> `keycloak/import/liferay-realm.json` puis `docker compose down -v && docker compose up -d`.

#### 1.b — Approche manuelle (équivalent dans la console Keycloak)

Si tu pars d'un realm existant (ou que tu veux ajouter le mapping après coup) :

**① Créer un groupe et y ajouter l'utilisateur**

1. Console Keycloak (<http://localhost:18280>) → sélectionne le realm **liferay**.
2. Menu **Groups** → **Create group** → nom : `editors` → **Create**.
3. Menu **Users** → clique sur ton utilisateur → onglet **Groups** → **Join Group**
   → coche `editors` → **Join**.

**② Ajouter le Group Membership mapper sur le client**

1. Menu **Clients** → clique sur **liferay**.
2. Onglet **Client scopes** → clique sur la ligne **liferay-dedicated**
   (ce sont les mappers spécifiques au client).
3. Onglet **Mappers** → **Configure a new mapper** → choisis
   **Group Membership** dans la liste.
4. Remplis le formulaire :

   | Champ                    | Valeur         |
   |--------------------------|----------------|
   | Name                     | `groups`       |
   | Token Claim Name         | `groups`       |
   | Full group path          | **Off**        |
   | Add to ID token          | **On**         |
   | Add to access token      | **On**         |
   | Add to userinfo          | **On**         |

5. **Save**.

> 💡 *Full group path* = `Off` renvoie `["editors"]` ; à `On` renverrait
> `["/editors"]`. Le module Liferay supporte les deux (il strippe le `/` initial),
> mais `Off` produit des noms de UserGroup Liferay plus propres.

#### 1.c — Vérifier que la claim est bien émise

**Option A — Depuis la console Keycloak**

1. **Clients → liferay → Client scopes** → bouton **Evaluate** en haut à droite.
2. Sélectionne ton utilisateur dans **Users** → clique **Evaluate**.
3. Onglet **Generated ID Token** : tu dois voir
   ```json
   "groups": ["editors"]
   ```

**Option B — Avec curl (authentification password grant)**

Récupère un token et inspecte la claim :

```bash
# Récupérer un token (remplace alice/Passw0rd! si besoin)
TOKEN=$(curl -s -X POST \
  "http://localhost:18280/realms/liferay/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=liferay" \
  -d "client_secret=liferay-secret-change-me" \
  -d "username=alice" \
  -d "password=Passw0rd!" \
  -d "scope=openid" \
  | jq -r .access_token)

# Décoder la partie payload du JWT
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq .
```

Sortie attendue (extrait) :
```json
{
  "preferred_username": "alice",
  "email": "alice@example.com",
  "groups": ["editors"]
}
```

**Option C — Depuis le userinfo endpoint**

```bash
curl -s "http://localhost:18280/realms/liferay/protocol/openid-connect/userinfo" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

Tu dois retrouver `"groups": ["editors"]` dans la réponse — c'est exactement ce
que Liferay lit côté module.

### 2. Builder le module Liferay

Aucun JDK requis sur ton poste : le build passe par un container Gradle.

**PowerShell (Windows)** :
```powershell
.\modules\keycloak-group-mapper\build.ps1
```

**Bash (WSL / Linux / macOS)** :
```bash
./modules/keycloak-group-mapper/build.sh
```

Le script :
1. Lance `gradle:8.5-jdk17` en container pour compiler et packager.
2. Copie le jar produit (`com.example.keycloak.group.mapper-1.0.0.jar`)
   **directement dans le container Liferay** via `docker cp` vers
   `/opt/liferay/osgi/modules/`.

> ⚠️ **Pourquoi pas via le volume `./liferay/deploy` ?** Sur les bind-mounts
> Windows/WSL, l'autodeploy de Liferay échoue à supprimer le jar après lecture
> et boucle indéfiniment avec « *Unable to write … .jar* ». `docker cp` évite
> le problème en posant directement le bundle dans le dossier de modules OSGi.

### 3. Vérifier l'installation

Liferay charge le bundle dans les 5-10 s :

```powershell
docker compose logs -f liferay | Select-String "keycloak\.group\.mapper"
```

Tu dois voir :
```
STARTED com.example.keycloak.group.mapper_1.0.0
```

### 4. Tester

1. Déconnecte-toi de Liferay (ou ouvre une fenêtre privée).
2. Reconnecte-toi via Keycloak en tant qu'`alice` / `Passw0rd!`.
3. Dans les logs Liferay :
   ```
   Created Liferay UserGroup 'editors' from Keycloak claim
   User alice@example.com synced with 1 UserGroup(s) from Keycloak
   ```
4. Admin Liferay → **Panneau de contrôle → Utilisateurs → User Groups** → `editors` doit exister.
5. Édite l'utilisateur → onglet **General → User Groups** → `editors` doit être assigné.

### 5. Personnaliser

| Pour…                                          | Modifier                                                                                                  |
|------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| Utiliser un autre nom de claim                  | Constante du nom (`"groups"`) dans `KeycloakGroupSyncPostLoginAction.java`.                               |
| Ne pas supprimer les groupes manuels            | Remplacer `setUserUserGroups` par `addUserUserGroups` (n'enlève rien, ajoute seulement).                  |
| Préfixer les groupes Keycloak (`kc_editors`)    | Modifier la boucle qui construit `name` pour ajouter un préfixe.                                          |
| Mapper aussi des rôles Liferay                  | Ajouter un appel à `RoleLocalService` (claim séparée `realm_access.roles` côté Keycloak).                 |

> Après chaque modification : relance `build.ps1` / `build.sh`. Liferay
> redémarre le bundle à chaud (pas de restart du container).

### 6. Dépendances et versions

Le `build.gradle` cible précisément Liferay 7.4.3.132 :

| Artefact                                                            | Version    |
|---------------------------------------------------------------------|------------|
| `com.liferay.portal:release.portal.api`                             | `7.4.3.132`|
| `com.liferay:com.liferay.portal.security.sso.openid.connect.api`    | `12.0.0`   |
| JDK de compilation                                                  | 17         |

Les deux artefacts Liferay viennent du Nexus public :
`https://repository.liferay.com/nexus/content/groups/public`.

> ⚠️ Si tu passes à une autre version de Liferay, vérifie la version du bundle
> `com.liferay.portal.security.sso.openid.connect.api` réellement présente
> dans le container et aligne `build.gradle` dessus, sinon le résolveur OSGi
> rejette le bundle (`Unresolved requirement: Import-Package`).

---

## Commandes utiles

```powershell
# Démarrer
docker compose up -d

# Voir l'état
docker compose ps

# Logs Liferay (suivi)
docker compose logs -f liferay

# Console Gogo OSGi de Liferay
docker exec -it liferay /bin/sh -c "telnet localhost 11311"

# Arrêter (en conservant les données)
docker compose stop

# Tout supprimer (containers + volumes => reset complet)
docker compose down -v
```

### Déployer un module Liferay

Déposez le `.jar` ou `.war` dans `./liferay/deploy/` — Liferay le détecte et l'installe automatiquement.

---

## Dépannage

| Symptôme                                              | Cause probable / solution                                                                                                  |
|-------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| `pull access denied for liferay/dxp`                  | Vous tentez de tirer l'image DXP (privée). Restez sur `liferay/portal:7.4.3.132-ga132` ou faites `docker login` avec un compte habilité par votre abonnement DXP. |
| Liferay reste sur *Starting Liferay*                  | Vérifiez la mémoire allouée à Docker (≥ 6 Go). Augmenter dans Docker Desktop → Settings → Resources.                       |
| `Connection refused` vers PostgreSQL                  | `liferay-db` n'est pas encore *healthy*. Le `depends_on` attend déjà — patientez 30 s.                                     |
| Erreur OIDC *Invalid redirect_uri*                    | Les **Valid redirect URIs** côté Keycloak doivent inclure `http://localhost:18080/*`.                                       |
| Erreur OIDC *Unknown issuer*                          | Le *Discovery Endpoint* doit pointer sur `http://keycloak:8080/...` (nom interne), pas `localhost`.                        |
| Boucle de redirection Keycloak ↔ Liferay              | Vérifiez que l'horloge des deux conteneurs est synchronisée et que `KC_HOSTNAME=localhost`.                                |
| Tag d'image introuvable                               | Listez les tags disponibles : <https://hub.docker.com/r/liferay/portal/tags> et ajustez la version dans le YAML.            |

---

## Références

- Documentation Liferay : <https://learn.liferay.com/>
- Image Docker Liferay CE : <https://hub.docker.com/r/liferay/portal>
- Image Docker Liferay DXP (privée) : <https://hub.docker.com/r/liferay/dxp>
- Documentation Keycloak : <https://www.keycloak.org/documentation>
- Connecteur OpenID Connect de Liferay : <https://learn.liferay.com/en/dxp/latest/en/system-administration/authentication/openid-connect.html>
