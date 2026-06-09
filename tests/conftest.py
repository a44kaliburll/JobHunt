import os
import sys
import tempfile
from pathlib import Path

# Isolate data dir + DB before jobhunt.config is imported anywhere.
_tmp = tempfile.mkdtemp(prefix="jobhunt-test-")
os.environ["JOBHUNT_DATA_DIR"] = _tmp
os.environ["JOBHUNT_DATABASE_URL"] = f"sqlite:///{Path(_tmp) / 'test.db'}"

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
