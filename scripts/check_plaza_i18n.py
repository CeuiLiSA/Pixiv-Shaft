#!/usr/bin/env python3
"""Check Plaza locale coverage, format arguments, and user-facing CJK literals."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
res = root / 'app/src/main/res'
locales = ['values', 'values-en', 'values-ja', 'values-ko', 'values-ru', 'values-tr', 'values-zh-rTW']
source = root / 'app/src/main/java/ceui/pixiv'
files = list((source / 'plaza').rglob('*.kt')) + [source / 'shaftapi/MediaUploader.kt']
errors = []
used = set()
for path in files:
    text = path.read_text()
    used.update(re.findall(r'R\.(string|plurals)\.(\w+)', text))
    for number, line in enumerate(text.splitlines(), 1):
        if re.search(r'"[^"\n]*[\u3400-\u9fff][^"\n]*"', line) and not line.lstrip().startswith('//'):
            errors.append(f'{path.relative_to(root)}:{number}: hardcoded CJK string')

def entries(locale):
    result = {}
    for path in (res / locale).glob('*.xml'):
        for element in ET.parse(path).getroot():
            if element.tag in ('string', 'plurals'):
                key = (element.tag, element.get('name'))
                if key in result:
                    errors.append(f'{locale}: duplicate {key}')
                result[key] = element
    return result

baseline = entries('values')
def formats(text):
    return sorted(set(re.findall(r'%(\d+\$[dsf])', text)))
for locale in locales:
    translated = entries(locale)
    for key in used:
        if key not in translated:
            errors.append(f'{locale}: missing {key}')
            continue
        source_element = baseline[key]
        element = translated[key]
        if key[0] == 'string':
            if formats(''.join(source_element.itertext())) != formats(''.join(element.itertext())):
                errors.append(f'{locale}: format mismatch {key}')
        else:
            required = {'other'} | ({'one', 'few', 'many'} if locale == 'values-ru' else {'one'} if locale in ('values-en', 'values-tr') else set())
            quantities = {item.get('quantity') for item in element}
            if not required <= quantities:
                errors.append(f'{locale}: missing plural forms {key}')
            for item in element:
                if formats(''.join(item.itertext())) != formats(''.join(source_element.find("item[@quantity='other']").itertext())):
                    errors.append(f'{locale}: plural format mismatch {key}')
if errors:
    raise SystemExit('\n'.join(errors))
print(f'Plaza: {len(used)} resources verified across {len(locales)} locales; no hardcoded CJK strings.')
