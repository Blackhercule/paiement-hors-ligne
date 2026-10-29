# Application mobile de paiement sans réseau

> Payer et recevoir de l'argent de téléphone à téléphone, **même quand le réseau mobile est coupé** :
> connexion Wi-Fi directe, scan d'un QR code et échanges chiffrés en AES-256.

Projet du **Groupe Pegasus** pour le **Hackathon des Universités**, Semaine du numérique 2026 (Burkina Faso).
Thème : *Souveraineté technologique : accélérer la transformation digitale sécurisée et inclusive.*

**Application Android native écrite en Java, Android 8.0 et plus.** Le dossier de présentation complet se trouve
dans [`docs/`](docs/).

## Le problème

Au Burkina Faso, le paiement mobile est devenu le principal moyen de paiement électronique, mais il s'arrête dès
que le réseau tombe : zones blanches, antennes détruites ou inaccessibles, coupures. Sans réseau, aucun paiement
de téléphone à téléphone n'est possible.

## La solution

| Étape | Téléphone B (receveur) | Téléphone A (payeur) |
|---|---|---|
| 1 | Ouvre « Recevoir » : crée un point d'accès Wi-Fi local et affiche un QR code | |
| 2 | | Ouvre « Envoyer » et scanne le QR code (en face-à-face) |
| 3 | Accepte **un seul** payeur ; toute autre connexion est refusée | Se connecte au point d'accès de B |
| 4 | Les deux téléphones calculent la même clé de session : **ECDH P-256 → HKDF-SHA-256 → AES-256** | |
| 5 | | Vérifie la signature de l'application, vérifie le solde, **débite**, envoie l'ordre chiffré |
| 6 | Vérifie, **crédite**, note l'identifiant du paiement, renvoie un reçu chiffré | Passe le paiement en « confirmé » |

Aucun réseau mobile, aucun USSD et aucun Internet ne sont utilisés au moment du paiement.

## Les écrans

- **Solde** : solde disponible, masquable par des points (••••••) pour la discrétion en public.
- **Envoyer** : scan du QR code, pavé numérique, confirmation.
- **Recevoir** : point d'accès local, QR code, réception en direct.
- **Historique** : toutes les opérations par jour, avec filtre et totaux.
- **Application falsifiée** : écran de blocage si l'intégrité n'est pas vérifiée.

## Sécurité

| Menace | Contre-mesure | Où dans le code |
|---|---|---|
| Écoute des échanges | AES-256-GCM, clé de session neuve à chaque paiement, effacée à la fin | `crypto/CanalChiffre`, `crypto/EchangeCles` |
| Faux receveur (homme du milieu) | Clé publique du receveur transmise par le QR code lu en face-à-face | `reseau/InfosConnexion` |
| Message modifié en route | GCM authentifie chaque message ; tout message altéré est rejeté | `crypto/ChiffrementAes` |
| Message rejoué ou réordonné | Chaque message porte son sens et son numéro d'ordre (données associées GCM) | `crypto/CanalChiffre` |
| Paiement crédité deux fois | Identifiant unique par paiement + journal anti-rejeu chez le receveur | `metier/Portefeuille` |
| Coupure pendant un paiement | Débit avant envoi ; le paiement reste « en attente » et il est renvoyé avec le même identifiant | `reseau/SessionPayeur` |
| Deuxième téléphone qui tente de se connecter | Le serveur du receveur n'accepte qu'un seul pair | `reseau/ServeurReception` |
| Solde modifié sur le téléphone | Portefeuille chiffré par une clé non exportable de l'**Android Keystore** (StrongBox si disponible) ; le solde doit égaler la somme de l'historique | `stockage/CoffreSecurise` |
| Application modifiée puis re-signée | Empreinte SHA-256 du certificat comparée à l'empreinte officielle, au démarrage et avant chaque paiement ; obfuscation R8 | `securite/VerificateurIntegrite` |
| Sauvegarde ou transfert du portefeuille | Sauvegarde Android et transfert d'appareil désactivés | `AndroidManifest.xml` |

**Limites connues :** comme toute monnaie électronique hors ligne, le risque principal est la double dépense par un
téléphone compromis (rooté), car aucun serveur ne vérifie l'opération au moment où elle a lieu. Les réponses
prévues sont dans la feuille de route : plafonds hors ligne, détection du root, synchronisation au retour du réseau.
La clé de signature fournie (`app/cle-demo.p12`) est une **clé de démonstration** publique, pour que le jury puisse
compiler l'application ; en production, la clé officielle reste secrète.

## Organisation du code

```
app/src/main/java/bf/pegasus/paiement/
├── ui/         SoldeActivity, EnvoyerActivity, RecevoirActivity, HistoriqueActivity, FalsifieeActivity
├── metier/     Portefeuille, Operation, Depot (modifications « tout ou rien »)
├── reseau/     PointAcces, ConnexionWifi, ServeurReception, SessionReceveur, SessionPayeur, ProtocoleTransfert
├── crypto/     EchangeCles (ECDH), Hkdf, ChiffrementAes, CanalChiffre
├── stockage/   CoffreSecurise (Android Keystore)
└── securite/   VerificateurIntegrite (signature de l'APK)
app/src/test/java/…   41 tests unitaires (dont une vraie connexion TCP entre deux téléphones simulés)
```

## Tester l'application

### 1. Installer

1. Ouvrir le dossier du projet dans **Android Studio**, puis laisser Gradle synchroniser.
2. Brancher un téléphone Android 8.0 ou plus en USB (débogage USB activé), puis cliquer sur **Run ▶**.
3. Faire de même avec le second téléphone. Autre méthode : *Build › Generate App Bundles or APKs › Generate APKs*,
   puis envoyer le fichier `app-debug.apk` sur le second téléphone.

Chaque téléphone démarre avec un **solde de démonstration de 50 000 FCFA**.

### 2. Scénarios à vérifier

| # | Scénario | Résultat attendu |
|---|---|---|
| 1 | B : « Recevoir ». A : « Envoyer », scanner, 2 500, Envoyer | A : 47 500, « Paiement confirmé ». B : 52 500, « Paiement reçu » |
| 2 | Données mobiles coupées sur les deux téléphones, puis refaire 1 | Fonctionne de la même façon |
| 3 | Un 3ᵉ téléphone scanne le même QR pendant la session | Refusé ; B affiche « Un autre téléphone a tenté de se connecter » |
| 4 | Montant supérieur au solde | Bouton bloqué, « Solde insuffisant » |
| 5 | Bouton œil sur l'accueil | Solde et montants remplacés par •••••• |
| 6 | B ferme « Recevoir » juste après l'envoi de A | A : « Paiement en attente ». À la connexion suivante avec B : renvoyé et confirmé, **B n'est pas crédité deux fois** |
| 7 | Variante falsifiée (ci-dessous) | « Application falsifiée », portefeuille bloqué |

### 3. Démontrer l'écran « Application falsifiée »

Dans Android Studio : *Build › Select Build Variant…*, choisir **falsifiee**, puis Run.
Cette variante est la même application, signée avec une **autre clé** : au démarrage, l'empreinte ne correspond
plus et l'application se bloque. Elle s'installe à côté de la version normale.

### 4. Tests unitaires

Dans Android Studio : clic droit sur `app/src/test/java`, puis **Run 'Tests in…'**.
En ligne de commande : `gradlew testDebugUnitTest`.

Ce qui est testé : chiffrement (dont le vecteur officiel de la RFC 5869), anti-rejeu, débit et crédit, coupure et
renvoi, falsification du solde, faux QR code, message modifié en route, intrus refusé.

Le workflow GitHub Actions (`.github/workflows/android.yml`) relance ces tests et produit les APK à chaque envoi
sur GitHub. Les APK se téléchargent ensuite dans l'onglet **Actions** du dépôt.

## Feuille de route

- **Phase 1, hackathon (ce prototype)** : 4 écrans, QR code et point d'accès, transfert chiffré AES-256, stockage
  sécurisé, contrôle de signature, démo sur 2 téléphones.
- **Phase 2** : code PIN ou empreinte digitale, plafonds hors ligne, détection du root, tests sur de nombreux modèles.
- **Phase 3** : recharge depuis un compte mobile money ou bancaire, synchronisation au retour du réseau,
  partenariat avec un établissement agréé (BCEAO).
- **Phase 4** : version iOS, Bluetooth et Wi-Fi Direct, interopérabilité entre opérateurs.

## Équipe : Groupe Pegasus

- YE Franck Ahmed
- KABORE Bienvenue
- TRAORE Hussen Izedine

## Licences

Code sous licence MIT (voir [LICENSE](LICENSE)). Police Manrope sous licence SIL Open Font License
(voir [docs/licences/Manrope-OFL.txt](docs/licences/Manrope-OFL.txt)). Lecture des QR codes : ZXing (Apache 2.0).
