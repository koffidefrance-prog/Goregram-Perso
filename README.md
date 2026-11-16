# GOREGRAM PERSO — messagerie indépendante

Cette branche contient une reconstruction indépendante de Telegram : client Android natif, API propriétaire et base PostgreSQL. L'ancien code Telegram est conservé séparément dans la branche `telegram-source-archive`; la branche `master` contient désormais le projet indépendant.

## État
- Authentification par numéro E.164 et OTP : API prête, nécessite un fournisseur SMS configuré.
- Profils, recherche exacte par numéro, conversations directes, messages texte et WebSocket : fondation serveur implémentée.
- Android Compose : interface de connexion et messagerie de base.
- Les appels audio/vidéo, groupes, pièces jointes, notifications push, chiffrement de bout en bout et synchronisation du carnet d'adresses ne sont pas encore implémentés.

## Serveur
Variables obligatoires : `DATABASE_URL`, `JWT_SECRET`, `OTP_HASH_SECRET`. En production, ajouter les identifiants d'un fournisseur SMS. Ne jamais utiliser de code OTP fixe en production.
Le schéma SQL est idempotent et préserve les tables existantes.

## Déploiement gratuit
Le service web gratuit Render peut se mettre en veille après inactivité. Neon Free fournit PostgreSQL avec quotas et mise en veille. Ces offres peuvent changer et ne garantissent pas une disponibilité 24/7. La livraison réelle des SMS peut entraîner des frais.

## Android
Ouvrir `android/` avec Android Studio ou utiliser le workflow GitHub Actions. L'URL API doit être configurée dans `android/app/build.gradle.kts` avant de distribuer l'APK.

## Sécurité
Transport HTTPS requis en production. Ce projet ne revendique pas le chiffrement de bout en bout. Faire auditer le système avant un lancement public.
