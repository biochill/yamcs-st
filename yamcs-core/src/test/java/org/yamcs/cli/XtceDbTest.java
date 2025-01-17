package org.yamcs.cli;

import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.yamcs.YConfiguration;

public class XtceDbTest extends AbstractCliTest {
    @Before
    public void resetConfig() {
        YConfiguration.setupTest(null);
    }

    @Test
    public void testXtceDbPrintCli() throws Exception {
        YamcsAdminCli yamcsCli = new YamcsAdminCli();
        yamcsCli.parse(new String[] { "mdb", "print", "refmdb" });
        yamcsCli.validate();
        yamcsCli.execute();

        String out = mconsole.output();
        assertTrue(out.contains("SpaceSystem /REFMDB"));
        assertTrue(out.contains("SequenceContainer name: PKT3"));
        assertTrue(out.contains("Algorithm name: ctx_param_test"));
        assertTrue(out.contains("MetaCommand name: CALIB_TC"));
    }

    @Test
    public void testXtceDbVerifyCli() throws Exception {
        YConfiguration.setupTest("src/test/resources/");

        YamcsAdminCli yamcsCli = new YamcsAdminCli();
        yamcsCli.parse(new String[] { "mdb", "verify", "refmdb" });
        yamcsCli.validate();
        yamcsCli.execute();
        String out = mconsole.output();
        assertTrue(out.contains("MDB loaded successfully"));
    }
}
