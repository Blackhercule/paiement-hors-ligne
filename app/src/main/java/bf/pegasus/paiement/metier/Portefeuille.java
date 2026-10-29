package bf.pegasus.paiement.metier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import bf.pegasus.paiement.crypto.Octets;

/**
 * Le portefeuille : solde, historique et journal anti-rejeu.
 *
 * <p>Règles d'or :</p>
 * <ul>
 *   <li>le payeur débite AVANT d'envoyer : l'argent ne peut pas être dupliqué ;</li>
 *   <li>le receveur note chaque identifiant de paiement reçu : un paiement renvoyé
 *       après une coupure n'est jamais crédité deux fois ;</li>
 *   <li>le solde doit toujours être égal à la somme de l'historique : sinon, falsification.</li>
 * </ul>
 *
 * <p>Cette classe n'est pas synchronisée : on y accède à travers {@link Depot}.</p>
 */
public final class Portefeuille {

    /** Solde de départ du prototype (voir section 10 du dossier). */
    public static final long SOLDE_DEMO = 50_000L;
    /** Montant maximum d'un paiement. */
    public static final long MONTANT_MAX = 1_000_000L;
    public static final int LONGUEUR_MAX_NOM = 30;

    private static final String ENTETE = "PHL1-portefeuille";

    private final String appareil;
    private String nom;
    private long solde;
    /** Plus récente en premier. */
    private final List<Operation> operations;
    /** Identifiants des paiements déjà reçus. */
    private final Set<String> journal;

    private Portefeuille(String appareil, String nom, long solde, List<Operation> operations, Set<String> journal) {
        this.appareil = appareil;
        this.nom = nom;
        this.solde = solde;
        this.operations = operations;
        this.journal = journal;
    }

    /** Nouveau portefeuille, crédité du solde de démonstration. */
    public static Portefeuille creer(String appareil, long soldeDemo, long maintenant) {
        Portefeuille p = new Portefeuille(appareil, "", 0, new ArrayList<Operation>(), new LinkedHashSet<String>());
        if (soldeDemo > 0) {
            p.operations.add(new Operation(Octets.identifiant(), Operation.Type.DEMO, Operation.Statut.CONFIRME,
                    soldeDemo, "", "", maintenant));
            p.solde = soldeDemo;
        }
        return p;
    }

    public Portefeuille copie() {
        return new Portefeuille(appareil, nom, solde, new ArrayList<>(operations), new LinkedHashSet<>(journal));
    }

    /** Identifiant de ce téléphone (16 caractères hexadécimaux). */
    public String appareil() {
        return appareil;
    }

    public String nom() {
        return nom;
    }

    public long solde() {
        return solde;
    }

    public List<Operation> operations() {
        return Collections.unmodifiableList(new ArrayList<>(operations));
    }

    public boolean dejaRecu(String idPaiement) {
        return journal.contains(idPaiement);
    }

    public void renommer(String nouveauNom) {
        this.nom = nettoyerNom(nouveauNom);
    }

    /**
     * Côté payeur : vérifie le solde puis le débite. L'opération reste « en attente »
     * jusqu'au reçu du receveur.
     */
    public Operation debiter(String destAppareil, String destNom, long montant, long maintenant)
            throws ErreurPaiement {
        if (montant <= 0) {
            throw new ErreurPaiement("Saisissez un montant.");
        }
        if (montant > MONTANT_MAX) {
            throw new ErreurPaiement("Montant trop élevé : 1 000 000 FCFA au maximum.");
        }
        if (montant > solde) {
            throw new ErreurPaiement("Solde insuffisant.");
        }
        if (appareil.equals(destAppareil)) {
            throw new ErreurPaiement("Vous ne pouvez pas vous payer vous-même.");
        }
        Operation op = new Operation(Octets.identifiant(), Operation.Type.ENVOI, Operation.Statut.EN_ATTENTE,
                montant, destAppareil, nettoyerNom(destNom), maintenant);
        solde -= montant;
        operations.add(0, op);
        return op;
    }

    /** Résultat du traitement d'un paiement reçu. */
    public static final class Reception {
        public enum Etat { ACCEPTE, DEJA_RECU, REFUSE }

        public final Etat etat;
        public final Operation operation;
        public final String motif;

        private Reception(Etat etat, Operation operation, String motif) {
            this.etat = etat;
            this.operation = operation;
            this.motif = motif;
        }
    }

    /** Côté receveur : crédite un paiement, sauf s'il a déjà été reçu (anti-rejeu). */
    public Reception crediter(String idPaiement, String deAppareil, String deNom, long montant, long maintenant) {
        if (journal.contains(idPaiement)) {
            return new Reception(Reception.Etat.DEJA_RECU, null, null);
        }
        if (!Octets.estHex(idPaiement, 32)) {
            return new Reception(Reception.Etat.REFUSE, null, "identifiant");
        }
        if (montant <= 0 || montant > MONTANT_MAX) {
            return new Reception(Reception.Etat.REFUSE, null, "montant");
        }
        if (appareil.equals(deAppareil)) {
            return new Reception(Reception.Etat.REFUSE, null, "identite");
        }
        Operation op = new Operation(idPaiement, Operation.Type.RECEPTION, Operation.Statut.RECU,
                montant, deAppareil, nettoyerNom(deNom), maintenant);
        solde += montant;
        journal.add(idPaiement);
        operations.add(0, op);
        return new Reception(Reception.Etat.ACCEPTE, op, null);
    }

    /** Côté payeur : le reçu est arrivé, l'envoi passe à « confirmé ». */
    public Operation confirmer(String idPaiement) {
        for (int i = 0; i < operations.size(); i++) {
            Operation op = operations.get(i);
            if (op.id.equals(idPaiement) && op.type == Operation.Type.ENVOI) {
                if (op.statut == Operation.Statut.EN_ATTENTE) {
                    op = op.avecStatut(Operation.Statut.CONFIRME);
                    operations.set(i, op);
                }
                return op;
            }
        }
        return null;
    }

    /** Envois sans reçu vers ce receveur : à renvoyer tels quels (même identifiant). */
    public List<Operation> enAttentePour(String destAppareil) {
        List<Operation> resultat = new ArrayList<>();
        for (int i = operations.size() - 1; i >= 0; i--) {
            Operation op = operations.get(i);
            if (op.type == Operation.Type.ENVOI && op.statut == Operation.Statut.EN_ATTENTE
                    && op.pairAppareil.equals(destAppareil)) {
                resultat.add(op);
            }
        }
        return resultat;
    }

    /** Contrôle de cohérence : le solde doit être exactement la somme de l'historique. */
    public void verifierIntegrite() throws Falsification {
        if (!Octets.estHex(appareil, 16)) {
            throw new Falsification("Identifiant du téléphone invalide.");
        }
        long total = 0;
        Set<String> vus = new HashSet<>();
        for (Operation op : operations) {
            if (!vus.add(op.id)) {
                throw new Falsification("Une opération apparaît deux fois dans l'historique.");
            }
            if (op.montant <= 0 || (op.type != Operation.Type.DEMO && op.montant > MONTANT_MAX)) {
                throw new Falsification("Montant d'opération invalide.");
            }
            if (op.type == Operation.Type.RECEPTION && !journal.contains(op.id)) {
                throw new Falsification("Journal anti-rejeu incohérent.");
            }
            total += op.effetSurSolde();
        }
        if (total != solde) {
            throw new Falsification("Le solde ne correspond pas à l'historique.");
        }
        if (solde < 0) {
            throw new Falsification("Solde négatif.");
        }
    }

    public String serialiser() {
        StringBuilder sb = new StringBuilder(ENTETE).append('\n');
        sb.append("etat ").append(new Message().avec("app", appareil).avec("nom", nom).avec("solde", solde).encoder())
                .append('\n');
        for (Operation op : operations) {
            sb.append("op ").append(op.encoder()).append('\n');
        }
        for (String id : journal) {
            sb.append("journal ").append(id).append('\n');
        }
        return sb.toString();
    }

    /** Relit un portefeuille enregistré et vérifie aussitôt sa cohérence. */
    public static Portefeuille charger(String texte) throws Falsification {
        try {
            String[] lignes = texte.split("\n");
            if (lignes.length < 2 || !ENTETE.equals(lignes[0]) || !lignes[1].startsWith("etat ")) {
                throw new Message.FormatInvalide("En-tête absent");
            }
            Message etat = Message.decoder(lignes[1].substring(5));
            List<Operation> ops = new ArrayList<>();
            Set<String> journal = new LinkedHashSet<>();
            for (int i = 2; i < lignes.length; i++) {
                String l = lignes[i];
                if (l.startsWith("op ")) {
                    ops.add(Operation.decoder(l.substring(3)));
                } else if (l.startsWith("journal ")) {
                    journal.add(l.substring(8));
                } else if (!l.isEmpty()) {
                    throw new Message.FormatInvalide("Ligne inconnue");
                }
            }
            Portefeuille p = new Portefeuille(etat.exiger("app"), nettoyerNom(etat.exiger("nom")),
                    etat.exigerNombre("solde"), ops, journal);
            p.verifierIntegrite();
            return p;
        } catch (Message.FormatInvalide e) {
            throw new Falsification("Portefeuille illisible : " + e.getMessage());
        }
    }

    /** Nom affiché : sans caractères de contrôle, 30 caractères au plus. */
    public static String nettoyerNom(String brut) {
        if (brut == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean espace = false;
        for (int i = 0; i < brut.length() && sb.length() < LONGUEUR_MAX_NOM; i++) {
            char c = brut.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                espace = sb.length() > 0;
                continue;
            }
            if (espace) {
                sb.append(' ');
                espace = false;
                if (sb.length() >= LONGUEUR_MAX_NOM) {
                    break;
                }
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }
}
