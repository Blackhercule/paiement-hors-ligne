"""Portefeuille hors ligne : clés Ed25519, plafond local, transactions signées."""
import base64
import json
import time
from dataclasses import dataclass, field

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)

PLAFOND_HORS_LIGNE = 20_000  # FCFA, plafond de dépenses sans réseau


def pub_hex(pub: Ed25519PublicKey) -> str:
    raw = pub.public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    return raw.hex()


def pub_from_hex(h: str) -> Ed25519PublicKey:
    return Ed25519PublicKey.from_public_bytes(bytes.fromhex(h))


def canonical(payload: dict) -> bytes:
    """Sérialisation déterministe pour la signature."""
    return json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()


def encoder(tx: dict) -> str:
    """Transaction -> texte compact (contenu d'un QR code, d'un tag NFC...)."""
    return base64.urlsafe_b64encode(json.dumps(tx).encode()).decode()


def decoder(jeton: str) -> dict:
    return json.loads(base64.urlsafe_b64decode(jeton.encode()))


def verifier_signature(tx: dict) -> None:
    pub = pub_from_hex(tx["p"]["de"])
    pub.verify(bytes.fromhex(tx["s"]), canonical(tx["p"]))  # lève InvalidSignature


@dataclass
class Wallet:
    nom: str
    solde_hors_ligne: int = 0   # montant rechargé EN LIGNE, dépensable sans réseau
    compteur: int = 0           # anti-rejeu : strictement croissant
    _cle: Ed25519PrivateKey = field(default_factory=Ed25519PrivateKey.generate)
    recus: list = field(default_factory=list)  # à synchroniser au retour du réseau

    @property
    def cle_publique(self) -> str:
        return pub_hex(self._cle.public_key())

    def recharger(self, montant: int) -> None:
        """Recharge validée par le serveur (compte principal débité)."""
        self.solde_hors_ligne = min(self.solde_hors_ligne + montant, PLAFOND_HORS_LIGNE)

    def payer(self, destinataire_pub: str, montant: int) -> str:
        """Crée un paiement signé, sans aucun réseau."""
        if montant <= 0:
            raise ValueError("Montant invalide")
        if montant > self.solde_hors_ligne:
            raise ValueError("Solde hors ligne insuffisant")
        self.compteur += 1
        payload = {
            "de": self.cle_publique,
            "a": destinataire_pub,
            "montant": montant,
            "compteur": self.compteur,
            "ts": int(time.time()),
        }
        signature = self._cle.sign(canonical(payload)).hex()
        self.solde_hors_ligne -= montant
        return encoder({"p": payload, "s": signature})

    def recevoir(self, jeton: str) -> dict:
        """Vérifie la signature localement, sans réseau."""
        tx = decoder(jeton)
        if tx["p"]["a"] != self.cle_publique:
            raise ValueError("Paiement destiné à quelqu'un d'autre")
        verifier_signature(tx)
        self.recus.append(tx)
        return tx["p"]
