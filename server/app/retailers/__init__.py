"""Retailer-specific knowledge: how one chain describes where a product physically sits.

Everything in here is **pure string parsing**. Cookbook's server never fetches a retailer page
itself — see :mod:`app.retailers.meijer` for why that is a deliberate constraint and not an
oversight. The observations arrive as a POSTed batch and these functions give them meaning.
"""
