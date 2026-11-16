# GOREGRAM PERSO — messagerie indépendante

Goregram Perso devient une application de messagerie indépendante. Cette branche repart d'une base neuve : aucun client Telegram, aucune API Telegram et aucun serveur Telegram.

## Architecture
- Android : Kotlin + Jetpack Compose.
- Serveur : Node.js, Express, PostgreSQL et WebSocket.
- Inscription : numéro de téléphone au format international E.164, vérification par SMS.
- Médias : stockage objet S3-compatible (à configurer avant la mise en production).
- Notifications : Firebase Cloud Messaging (à configurer).

## Démarrage du serveur
1. Copier `.env.example` vers `.env` et renseigner les secrets.
2. Lancer `docker compose up --build`.
3. L'API écoute sur le port 8080.

Sans identifiants SMS, l'envoi OTP échoue volontairement en mode production. Ne jamais activer de code OTP fixe en production.

## Android
Ouvrir le dossier `android/` dans Android Studio ou lancer `./gradlew assembleDebug` dans un environnement Android configuré. Définir l'URL de l'API via `API_BASE_URL`.

## Sécurité et limites
Le code fournit une fondation d'authentification et de transport temps réel, pas encore un produit prêt pour des utilisateurs réels. Avant publication : configurer un fournisseur SMS, FCM, stockage privé, sauvegardes, supervision, politique de confidentialité, tests de charge et audit de sécurité. Le chiffrement de bout en bout n'est pas encore implémenté et ne doit pas être revendiqué.
