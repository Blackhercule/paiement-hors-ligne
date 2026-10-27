"""Démo : paiement sans réseau, puis synchronisation et détection de fraude."""
from src.ledger import Registre
from src.wallet import Wallet

registre = Registre()
awa, issouf, fatou = Wallet("Awa"), Wallet("Issouf"), Wallet("Fatou")
registre.ouvrir_compte(awa.cle_publique, 50_000)
registre.ouvrir_compte(issouf.cle_publique, 0)
registre.ouvrir_compte(fatou.cle_publique, 0)

# 1. EN LIGNE : Awa recharge son portefeuille hors ligne
awa.recharger(registre.recharger_hors_ligne(awa.cle_publique, 10_000))
print(f"[en ligne]   Awa : solde hors ligne = {awa.solde_hors_ligne} FCFA")

# 2. HORS LIGNE : paiement légitime Awa -> Issouf
jeton = awa.payer(issouf.cle_publique, 3_000)
print("[hors ligne] Issouf vérifie le paiement :", issouf.recevoir(jeton)["montant"], "FCFA")

# 3. HORS LIGNE : fraude, Awa restaure un ancien état (même compteur) et paie Fatou
awa.compteur -= 1
awa.solde_hors_ligne += 3_000
jeton_fraude = awa.payer(fatou.cle_publique, 3_000)
print("[hors ligne] Fatou vérifie le paiement :", fatou.recevoir(jeton_fraude)["montant"], "FCFA")

# 4. RETOUR EN LIGNE : synchronisation
print("[sync]       Issouf :", registre.synchroniser(issouf.recus[0]))
print("[sync]       Fatou  :", registre.synchroniser(fatou.recus[0]))
print("Journal des fraudes :", registre.journal)
