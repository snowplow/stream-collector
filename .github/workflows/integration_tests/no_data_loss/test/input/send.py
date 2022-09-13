#!/usr/bin/python3

import argparse
import json
import ssl
from urllib.request import urlopen
from urllib.request import Request

HEADERS = {
    "Accept": "*/*",
    "Content-Type": "application/json; charset=UTF-8"
}

COLLECTOR_ENDPOINT = 'http://0.0.0.0:12345/com.snowplowanalytics.snowplow/tp2'

# Dirty hack, see: https://stackoverflow.com/questions/27835619/urllib-and-ssl-certificate-verify-failed-error
gcontext = ssl.SSLContext()

def send(manifest_file):
    with open(manifest_file) as manifest:
        lines = manifest.readlines()
        requests = []
        for line in lines:
            payload = json.loads(line)
            req = Request(
                COLLECTOR_ENDPOINT,
                json.dumps(payload).encode('ascii'),
                HEADERS
            )
            requests.append(req)

    responses = []

    for r in requests:
        with urlopen(r, context=gcontext) as response:
            response_content = response.read().decode('utf-8')
            responses.append(response_content)

    oks = list(filter(lambda r: r == 'ok', responses))

    print("Successfully sent: " + str(len(oks)) + " events.")

parser = argparse.ArgumentParser(description='Send the events from the specified manifest.')
parser.add_argument('manifest', help='A manifest of events to process.')
args = parser.parse_args()

if __name__ == '__main__':
    send(args.manifest)
