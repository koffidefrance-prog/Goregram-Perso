# Goregram Android indépendant

Cette application est le nouveau client Android Goregram. Elle est construite comme un projet Android autonome sous `goregram-android/` et ne dépend pas du client Telegram/MTProto historique.

## Fonctions présentes
- Création de compte et connexion par numéro international + mot de passe.
- Validation des liens d'invitation.
- Liste des conversations Goregram.
- Recherche d'un compte Goregram par numéro.
- Création de conversation directe et échange de messages texte.
- Invitation d'un numéro non inscrit par SMS si le fournisseur SMS du serveur est configuré, sinon partage du lien Android.

## Backend
API : `https://goregram-api-production.up.railway.app`

Le backend et PostgreSQL Neon restent indépendants de Telegram. Un numéro qui n'a pas de compte Goregram doit d'abord rejoindre Goregram pour recevoir les messages dans cette application.

## Compilation
Le workflow GitHub Actions `.github/workflows/build-goregram.yml` compile ce projet avec `./gradlew -p goregram-android assembleDebug` et publie l'APK comme artefact.

## Migration en cours
Le code historique du fork Telegram est conservé dans les anciens dossiers du dépôt et dans la branche de sauvegarde. Le nouveau client ne l'importe pas et son workflow ne compile que `goregram-android`. La suppression physique de tout l'ancien arbre Telegram sera faite après validation de la compilation et de la migration, pour éviter de supprimer des fichiers nécessaires avant d'avoir un APK fonctionnel.
