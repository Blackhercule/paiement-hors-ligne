"""Registre serveur : synchronisation, anti double-paiement, blocage des fraudeurs."""
from cryptography.exceptions import InvalidSignature

from .wallet import PLAFOND_HORS_LIGNE, verifier_signature


class Registre:
    def __init__(self) -> None:
        self.soldes: dict[str, int] = {}             # compte principal (en ligne)
        self.alloue_hors_ligne: dict[str, int] = {}  # montant autorisé hors ligne restant
        self.vus: dict[tuple, dict] = {}             # (payeur, compteur) -> transaction
        self.bloques: set[str] = set()
        self.journal: list[str] = []

    def ouvrir_compte(self, pub: str, solde: int) -> None:
        self.soldes[pub] = solde
        self.alloue_hors_ligne[pub] = 0

    def recharger_hors_ligne(self, pub: str, montant: int) -> int:
        """Débite le compte principal et autorise ce montant hors ligne (plafonné)."""
        montant = min(montant, PLAFOND_HORS_LIGNE - self.alloue_hors_ligne[pub])
        if montant <= 0 or self.soldes[pub] < montant:
            raise ValueError("Recharge impossible")
        self.soldes[pub] -= montant
        self.alloue_hors_ligne[pub] += montant
        return montant

    def synchroniser(self, tx: dict) -> str:
        """Traite une transaction reçue hors ligne. Retourne 'ok' ou le motif de rejet."""
        p = tx["p"]
        payeur, cle = p["de"], (p["de"], p["compteur"])

        try:
            verifier_signature(tx)
        except InvalidSignature:
            return self._rejet(f"signature invalide ({payeur[:8]})")

        if payeur in self.bloques:
            return self._rejet(f"payeur bloqué ({payeur[:8]})")

        if cle in self.vus:
            if self.vus[cle]["s"] == tx["s"]:
                return "ok (déjà synchronisée)"
            self.bloques.add(payeur)
            return self._rejet(f"DOUBLE PAIEMENT détecté, compte bloqué ({payeur[:8]})")

        if self.alloue_hors_ligne.get(payeur, 0) < p["montant"]:
            self.bloques.add(payeur)
            return self._rejet(f"dépassement du plafond hors ligne, compte bloqué ({payeur[:8]})")

        self.vus[cle] = tx
        self.alloue_hors_ligne[payeur] -= p["montant"]
        self.soldes[p["a"]] = self.soldes.get(p["a"], 0) + p["montant"]
        return "ok"

    def _rejet(self, motif: str) -> str:
        self.journal.append(motif)
        return f"rejeté : {motif}"
