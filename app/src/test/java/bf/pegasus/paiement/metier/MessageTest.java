package bf.pegasus.paiement.metier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MessageTest {

    @Test
    public void lesCaracteresSpeciauxTraversentSansProbleme() throws Exception {
        Message m = Message.de("paiement").avec("nom", "Awa & Fils = 1 · été\nà Ouaga").avec("montant", 2500);
        Message relu = Message.decoder(m.encoder());
        assertEquals("paiement", relu.type());
        assertEquals("Awa & Fils = 1 · été\nà Ouaga", relu.get("nom"));
        assertEquals(2500, relu.exigerNombre("montant"));
        assertNull(relu.get("absent"));
    }

    @Test(expected = Message.FormatInvalide.class)
    public void unChampEnDoubleEstRefuse() throws Exception {
        Message.decoder("montant=1&montant=1000000");
    }

    @Test(expected = Message.FormatInvalide.class)
    public void unChampManquantEstSignale() throws Exception {
        Message.decoder("type=paiement").exiger("montant");
    }

    @Test(expected = Message.FormatInvalide.class)
    public void unNombreInvalideEstSignale() throws Exception {
        Message.decoder("montant=beaucoup").exigerNombre("montant");
    }

    @Test(expected = Message.FormatInvalide.class)
    public void unTexteMalFormeEstRefuse() throws Exception {
        Message.decoder("sans-egal");
    }
}
