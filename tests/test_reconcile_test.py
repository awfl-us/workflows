import importlib.machinery
import importlib.util
from pathlib import Path
import unittest

FILE=Path(__file__).resolve().parent.parent/'scripts/reconcile-test'
loader=importlib.machinery.SourceFileLoader('awfl_reconcile_test', str(FILE))
spec=importlib.util.spec_from_loader(loader.name,loader)
mod=importlib.util.module_from_spec(spec)
loader.exec_module(mod)

class ReconcileLauncherTest(unittest.TestCase):
    def test_missing_server_fails_doctor(self):
        self.assertEqual(mod.main(['doctor','--server','/tmp/no-reconcile-server','--compiler','/tmp/no-compiler']),1)
    def test_argument_validation_rejects_unknown(self):
        with self.assertRaises(SystemExit):
            mod.main(['bad-command'])
    def test_missing_report_is_actionable(self):
        with self.assertRaises(SystemExit) as raised:
            mod.main(['basic','--server','/tmp/no-reconcile-server'])
        self.assertIn('Missing server',str(raised.exception))

if __name__=='__main__':unittest.main()
