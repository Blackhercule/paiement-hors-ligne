import pytest
from cryptography.exceptions import InvalidSignature

from src.ledger import Registre
from src.wallet import Wallet, decoder, encoder


def monde(solde=50_000, recharge=10_000):
    r = Registre()
    a, b, c = Wallet("A"), Wallet("B"), Wallet("C")
    for w in (a, b, c):
        r.ouvrir_compte(w.cle_publique, solde if w is a else 0)
    a.recharger(r.recharger_hors_ligne(a.cle_publique, recharge))
    return r, a, b, c


def test_paiement_normal():
    r, a, b, _ = monde()
    b.recevoir(a.payer(b.cle_publique, 3_000))
    assert r.synchroniser(b.recus[0]) == "ok"
    assert r.soldes[b.cle_publique] == 3_000


def test_solde_insuffisant():
    _, a, b, _ = monde(recharge=1_000)
    with pytest.raises(ValueError):
        a.payer(b.cle_publique, 5_000)


def test_signature_falsifiee_rejetee():
    _, a, b, _ = monde()
    tx = decoder(a.payer(b.cle_publique, 1_000))
    tx["p"]["montant"] = 9_999
    with pytest.raises(InvalidSignature):
        b.recevoir(encoder(tx))


def test_double_paiement_detecte_et_compte_bloque():
    r, a, b, c = monde()
    b.recevoir(a.payer(b.cle_publique, 3_000))
    a.compteur -= 1
    a.solde_hors_ligne += 3_000
    c.recevoir(a.payer(c.cle_publique, 3_000))
    assert r.synchroniser(b.recus[0]) == "ok"
    assert "DOUBLE PAIEMENT" in r.synchroniser(c.recus[0])
    assert a.cle_publique in r.bloques


def test_rejeu_meme_transaction_idempotent():
    r, a, b, _ = monde()
    b.recevoir(a.payer(b.cle_publique, 2_000))
    r.synchroniser(b.recus[0])
    assert "déjà" in r.synchroniser(b.recus[0])
    assert r.soldes[b.cle_publique] == 2_000


def test_plafond_hors_ligne_respecte():
    r, a, b, _ = monde(recharge=5_000)
    a.solde_hors_ligne = 20_000  # triche locale : le serveur ne l'a pas autorisé
    b.recevoir(a.payer(b.cle_publique, 15_000))
    assert "plafond" in r.synchroniser(b.recus[0])
    assert a.cle_publique in r.bloques
