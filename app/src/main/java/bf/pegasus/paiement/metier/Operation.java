package bf.pegasus.paiement.metier;

/** Une ligne de l'historique : solde de démonstration, paiement envoyé ou paiement reçu. */
public final class Operation {

    public enum Type { DEMO, ENVOI, RECEPTION }

    /** Un envoi est « en attente » jusqu'au reçu du receveur, puis « confirmé ». */
    public enum Statut { EN_ATTENTE, CONFIRME, RECU }

    public final String id;
    public final Type type;
    public final Statut statut;
    public final long montant;
    /** Identifiant de l'autre téléphone (vide pour le solde de démonstration). */
    public final String pairAppareil;
    public final String pairNom;
    /** Date et heure de l'opération, en millisecondes. */
    public final long horodatage;

    public Operation(String id, Type type, Statut statut, long montant,
                     String pairAppareil, String pairNom, long horodatage) {
        this.id = id;
        this.type = type;
        this.statut = statut;
        this.montant = montant;
        this.pairAppareil = pairAppareil == null ? "" : pairAppareil;
        this.pairNom = pairNom == null ? "" : pairNom;
        this.horodatage = horodatage;
    }

    public Operation avecStatut(Statut nouveau) {
        return new Operation(id, type, nouveau, montant, pairAppareil, pairNom, horodatage);
    }

    /** Effet de l'opération sur le solde : négatif pour un envoi. */
    public long effetSurSolde() {
        return type == Type.ENVOI ? -montant : montant;
    }

    String encoder() {
        return new Message()
                .avec("id", id)
                .avec("type", type.name())
                .avec("statut", statut.name())
                .avec("montant", montant)
                .avec("pair", pairAppareil)
                .avec("nom", pairNom)
                .avec("ts", horodatage)
                .encoder();
    }

    static Operation decoder(String texte) throws Message.FormatInvalide {
        Message m = Message.decoder(texte);
        try {
            return new Operation(
                    m.exiger("id"),
                    Type.valueOf(m.exiger("type")),
                    Statut.valueOf(m.exiger("statut")),
                    m.exigerNombre("montant"),
                    m.exiger("pair"),
                    m.exiger("nom"),
                    m.exigerNombre("ts"));
        } catch (IllegalArgumentException e) {
            throw new Message.FormatInvalide("Opération invalide");
        }
    }
}
