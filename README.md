# Paiement mobile hors ligne

> Payer et être payé par téléphone **même sans réseau**, avec une sécurité vérifiable.
> Projet candidat au **Hackathon des Universités** – Semaine du numérique 2026 (Burkina Faso).
> Thème : *Souveraineté technologique – accélérer la transformation digitale sécurisée et inclusive.*

**Statut :** prototype de preuve de concept (cryptographie + registre de synchronisation). Voir la [feuille de route](#feuille-de-route).

## Le problème

Le paiement mobile suppose une connexion permanente. Or, au Burkina Faso :
- de nombreuses zones sont mal couvertes ou en zone blanche ;
- les coupures réseau et d'électricité sont fréquentes ;
- des populations déplacées ou isolées sont exclues des services financiers numériques ;
- toute la chaîne dépend d'infrastructures et de serveurs que le pays ne maîtrise pas toujours.

Quand le réseau tombe, le commerce s'arrête ou retourne au cash, avec les risques de vol et d'exclusion que cela implique.

## La solution

Un portefeuille numérique qui fonctionne en **trois temps** :

1. **En ligne – recharge** : l'utilisateur transfère un montant plafonné de son compte vers son portefeuille hors ligne.
2. **Hors ligne – paiement** : le payeur génère un jeton **signé cryptographiquement** (clé Ed25519 propre à son téléphone) et le transmet au commerçant par **QR code** (puis NFC / Bluetooth). Le commerçant vérifie la signature **sans réseau**.
3. **De retour en ligne – synchronisation** : le commerçant envoie les jetons reçus au registre, qui vérifie, détecte les fraudes et crédite le compte.

```
   EN LIGNE            HORS LIGNE (aucun réseau)             EN LIGNE
 ┌──────────┐       ┌──────────┐   jeton signé    ┌──────────┐      ┌──────────┐
 │ Registre │──────▶│ Payeur   │────(QR/NFC)─────▶│Commerçant│─────▶│ Registre │
 │ recharge │ plafond│ wallet   │                  │ vérifie  │ sync │ contrôle │
 └──────────┘       └──────────┘                  └──────────┘      └──────────┘
```

## Sécurité : le défi du double paiement

Sans réseau, rien n'empêche techniquement de dépenser deux fois la même somme (par exemple en restaurant une sauvegarde du téléphone). Notre réponse repose sur plusieurs couches :

| Menace | Contre-mesure |
|---|---|
| Falsification d'un paiement | Signature Ed25519 vérifiée par le commerçant et par le registre |
| Rejeu d'un même jeton | Compteur strictement croissant ; synchronisation idempotente |
| Double paiement (même compteur, bénéficiaires différents) | Détection au registre, **blocage du compte** fautif |
| Dépense au-delà de ce qui a été rechargé | Le registre ne valide que dans la limite du montant alloué |
| Perte importante en cas de fraude | **Plafond hors ligne** (20 000 FCFA dans ce prototype) |

**Limites connues (assumées) :** un double paiement n'est détecté qu'à la synchronisation, pas en temps réel. Le plafond borne donc la perte maximale. En production, la clé privée serait protégée par le matériel du téléphone (Android Keystore / Secure Enclave) pour rendre le clonage très difficile.

## Pourquoi c'est un projet de souveraineté et d'inclusion

- **Souveraineté** : le paiement ne dépend ni d'un opérateur étranger ni d'une connexion permanente ; le registre peut être hébergé localement.
- **Inclusion** : zones rurales, déplacés, commerçants sans connexion stable.
- **Sécurité** : cryptographie standard et ouverte, vérifiable par tous (code sous licence MIT).

## Installation et démonstration

```bash
git clone https://github.com/TON-PSEUDO/paiement-hors-ligne.git
cd paiement-hors-ligne
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

python demo.py          # scénario complet avec détection de fraude
python -m pytest -q     # tests unitaires
```

Sortie attendue de la démo : un paiement légitime synchronisé, puis un double paiement rejeté et le compte fraudeur bloqué.

## Structure du dépôt

```
src/wallet.py    portefeuille hors ligne, signature et vérification
src/ledger.py    registre : synchronisation, anti double-paiement, blocage
demo.py          scénario de démonstration
tests/           tests unitaires (fraude, plafond, falsification, rejeu)
```

## Feuille de route

- [x] Cryptographie des jetons, plafond, détection de double paiement (prototype)
- [ ] Application mobile (échange par QR code)
- [ ] Transport NFC et Bluetooth
- [ ] Interface USSD / SMS pour les téléphones basiques
- [ ] Clé protégée par le matériel du téléphone
- [ ] Interopérabilité avec les opérateurs et conformité réglementaire (BCEAO)
- [ ] Interface vocale en mooré, dioula et fulfuldé

## Équipe

- Scientifique – cybersécurité et tests d'intrusion
- *(à compléter : coéquipiers, université/école)*

## Licence

MIT – voir [LICENSE](LICENSE).
