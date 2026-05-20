# Liferay DXP 2026.Q1 + Keycloak — Stack Docker Compose

Stack de développement pour Liferay DXP 2026.Q1 intégré à Keycloak via OpenID Connect (OIDC).

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
- Accès Docker Hub authentifié pour `liferay/dxp` (image privée nécessitant un compte Liferay avec abonnement DXP)

```powershell
docker login
```

> ℹ️ **Image Liferay DXP** : `liferay/dxp:2026.q1.0`. Vérifiez le tag exact disponible sur [hub.docker.com/r/liferay/dxp/tags](https://hub.docker.com/r/liferay/dxp/tags) — adaptez si besoin (`2026.q1.1`, `2026.q1.2`, etc.).

---

## Architecture

| Service       | Image                              | Port hôte | Rôle                                  |
|---------------|------------------------------------|-----------|---------------------------------------|
| `liferay`     | `liferay/dxp:2026.q1.0`            | 8080      | Portail Liferay DXP                   |
| `liferay-db`  | `postgres:16`                      | —         | Base `lportal` pour Liferay           |
| `keycloak`    | `quay.io/keycloak/keycloak:26.0`   | 8180      | IAM / fournisseur OIDC                |
| `keycloak-db` | `postgres:16`                      | —         | Base `keycloak`                       |

Tous les services partagent le réseau `liferay-net` ; ils s'adressent entre eux par nom de service (ex. `http://keycloak:8080` depuis le conteneur Liferay).

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

1. Ouvrez [http://localhost:8080](http://localhost:8080).
2. Connectez-vous avec le compte admin par défaut :
   - Identifiant : `test@liferay.com`
   - Mot de passe : `test`
3. Liferay demande la création d'un nouveau mot de passe et d'une question de sécurité.
4. Acceptez les CGU.

---

## Configuration de Keycloak

### 1. Accès à la console admin

- URL : [http://localhost:8180](http://localhost:8180)
- Identifiants : `admin` / `admin`

### 2. Créer un realm dédié

1. Menu déroulant en haut à gauche → **Create Realm**.
2. Nom : `liferay`.
3. **Create**.

### 3. Créer le client OIDC pour Liferay

1. Menu **Clients** → **Create client**.
2. Étape *General settings* :
   - **Client type** : `OpenID Connect`
   - **Client ID** : `liferay`
   - **Next**.
3. Étape *Capability config* :
   - **Client authentication** : `On`
   - **Standard flow** : coché
   - **Direct access grants** : coché
   - **Next**.
4. Étape *Login settings* :
   - **Root URL** : `http://localhost:8080`
   - **Home URL** : `http://localhost:8080`
   - **Valid redirect URIs** : `http://localhost:8080/*`
   - **Valid post logout redirect URIs** : `http://localhost:8080/*`
   - **Web origins** : `http://localhost:8080`
   - **Save**.
5. Onglet **Credentials** → copiez la valeur **Client Secret** (utile à l'étape Liferay).

### 4. Créer un utilisateur de test

1. Menu **Users** → **Add user**.
2. **Username** : `alice`, **Email** : `alice@example.com`, **Email verified** : `On`, **First name** / **Last name** au choix.
3. **Create**.
4. Onglet **Credentials** → **Set password** → ex. `Passw0rd!`, **Temporary** : `Off`.

---

## Configuration de Liferay (OpenID Connect)

Liferay DXP intègre nativement OpenID Connect via le module **OpenID Connect Identity Provider Connection**.

### 1. Activer OpenID Connect

1. Connecté en admin → **Panneau de configuration** → **Paramètres système** → recherchez **OpenID Connect**.
2. Section **OpenID Connect → User Management** :
   - **Enabled** : `On`
   - **Save**.

### 2. Déclarer le fournisseur Keycloak

1. **Panneau de contrôle** → **Sécurité** → **OpenID Connect**.
2. **Ajouter** un fournisseur :
   - **Nom** : `Keycloak`
   - **Client ID** : `liferay`
   - **Client Secret** : *(valeur copiée depuis Keycloak)*
   - **Scopes** : `openid profile email`
   - **Discovery Endpoint** :
     ```
     http://keycloak:8080/realms/liferay/.well-known/openid-configuration
     ```
     > ⚠️ Depuis le conteneur Liferay, utilisez le nom de service `keycloak` (pas `localhost`). Le port interne de Keycloak est `8080`.
3. **Enregistrer**.

### 3. (Optionnel) Création automatique des comptes

Dans **Paramètres système → OpenID Connect** :

- **Strangers can create accounts** ou la propriété `users.email.address.required` doivent être cohérents avec les claims Keycloak (`email`, `given_name`, `family_name`).
- Si vous voulez créer automatiquement l'utilisateur lors de la première connexion, vérifiez que **Token Refresh Offset** et **User Profile Mapping** sont configurés (mapping par défaut OK pour Keycloak).

---

## Connexion via Keycloak

1. Déconnectez-vous de Liferay.
2. Sur la page de connexion, cliquez sur **Sign In with OpenID Connect** (ou le widget *Connexion OpenID Connect*).
3. Sélectionnez le fournisseur **Keycloak** → redirection vers Keycloak.
4. Saisissez `alice` / `Passw0rd!`.
5. Vous êtes redirigé sur Liferay, automatiquement loggué (le compte est créé au vol).

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
| `pull access denied for liferay/dxp`                  | Authentifiez-vous : `docker login` avec un compte habilité par votre abonnement Liferay DXP.                              |
| Liferay reste sur *Starting Liferay*                  | Vérifiez la mémoire allouée à Docker (≥ 6 Go). Augmenter dans Docker Desktop → Settings → Resources.                       |
| `Connection refused` vers PostgreSQL                  | `liferay-db` n'est pas encore *healthy*. Le `depends_on` attend déjà — patientez 30 s.                                     |
| Erreur OIDC *Invalid redirect_uri*                    | Les **Valid redirect URIs** côté Keycloak doivent inclure `http://localhost:8080/*`.                                       |
| Erreur OIDC *Unknown issuer*                          | Le *Discovery Endpoint* doit pointer sur `http://keycloak:8080/...` (nom interne), pas `localhost`.                        |
| Boucle de redirection Keycloak ↔ Liferay              | Vérifiez que l'horloge des deux conteneurs est synchronisée et que `KC_HOSTNAME=localhost`.                                |
| Tag d'image introuvable                               | Listez les tags disponibles : `https://hub.docker.com/r/liferay/dxp/tags` et ajustez `liferay/dxp:2026.q1.X` dans le YAML. |

---

## Références

- Documentation Liferay DXP : <https://learn.liferay.com/>
- Image Docker Liferay DXP : <https://hub.docker.com/r/liferay/dxp>
- Documentation Keycloak : <https://www.keycloak.org/documentation>
- Connecteur OpenID Connect de Liferay : <https://learn.liferay.com/en/dxp/latest/en/system-administration/authentication/openid-connect.html>
