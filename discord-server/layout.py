"""Picks which server layout the tools build.

Each server has its own layout file and its own state file:
    Evade Client:  layout_evade.py     state.json            (default)
    Vendetta:      layout_vendetta.py  state-vendetta.json

Select one with the SERVER environment variable, e.g.  SERVER=vendetta python3 setup_rest.py GUILD_ID
"""

import importlib
import os

SERVER = os.environ.get("SERVER", "evade")
_mod = importlib.import_module(f"layout_{SERVER}")
globals().update({k: v for k, v in vars(_mod).items() if not k.startswith("__")})
