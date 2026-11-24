"""Test de non-rejeu WS-Security.

Envoie deux fois exactement la même requête SOAP signée (même nonce, même date) directement au service SOAP ; la seconde
doit être refusée. Affiche « 200 500 » si tout va bien.

Usage : python tools/test-wss-replay.py
"""
import base64
import datetime
import hashlib
import os
import re
import urllib.error
import urllib.request

ENV = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".env")
password = re.search(r"^EHR_SERVICE_PASSWORD=(.*)$", open(ENV, encoding="utf-8").read(), re.M).group(1).strip()

nonce = os.urandom(16)
created = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
digest = base64.b64encode(hashlib.sha1(nonce + created.encode() + password.encode()).digest()).decode()
XML = f"""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ehr="http://smarthealthcare.tn/ehr">
<soapenv:Header><wsse:Security xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
<wsse:UsernameToken><wsse:Username>ehr-client</wsse:Username>
<wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">{digest}</wsse:Password>
<wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">{base64.b64encode(nonce).decode()}</wsse:Nonce>
<wsu:Created>{created}</wsu:Created></wsse:UsernameToken></wsse:Security></soapenv:Header>
<soapenv:Body><ehr:verifierDroitsCNAMRequest><ehr:patientId>PAT-000002</ehr:patientId><ehr:codeActe>CS</ehr:codeActe></ehr:verifierDroitsCNAMRequest></soapenv:Body></soapenv:Envelope>"""


def envoyer():
    req = urllib.request.Request("http://localhost:8081/ws/ehr", data=XML.encode(), headers={"Content-Type": "text/xml"})
    try:
        return urllib.request.urlopen(req, timeout=10).status
    except urllib.error.HTTPError as e:
        return e.code


premier = envoyer()
second = envoyer()
print(premier, second)  # attendu : 200 500
