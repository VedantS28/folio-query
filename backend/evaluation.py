"""Evaluate Folio with synthetic files.

Install dependencies: python -m pip install reportlab openpyxl
Run against a local backend: python evaluation.py
Run against another backend: python evaluation.py --base https://example.com
The script sends only generated synthetic content to the selected backend.
"""
import argparse
import io
import json
import sys
import urllib.parse
import urllib.request

from openpyxl import Workbook
from reportlab.pdfgen import canvas


def main():
    parser = argparse.ArgumentParser(description="Run synthetic Folio API regression checks")
    parser.add_argument("--base", default="http://127.0.0.1:8765")
    base = parser.parse_args().base.rstrip("/")

    def extract(name, data):
        request = urllib.request.Request(
            base + "/api/extract?name=" + urllib.parse.quote(name),
            data=data,
            headers={"Content-Type": "application/octet-stream"},
        )
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)

    def text_file(name, content):
        return extract(name, content.encode("utf-8"))

    resume_text = """Maya Rao
Education
Northbridge Institute of Technology
Bachelor of Engineering, Aug 2019 - Jun 2023
Technical Skills
Languages: Java, Go, Python
Backend: Spring Boot, Kafka
Testing: JUnit, Mockito
Experience
Built distributed services."""
    pdf_buffer = io.BytesIO()
    pdf = canvas.Canvas(pdf_buffer)
    pdf_text = pdf.beginText(48, 760)
    for line in resume_text.splitlines():
        pdf_text.textLine(line)
    pdf.drawText(pdf_text)
    pdf.save()
    resume = extract("synthetic-resume.pdf", pdf_buffer.getvalue())

    workbook = Workbook()
    worksheet = workbook.active
    worksheet.title = "Sales"
    worksheet.append(["Region", "Revenue"])
    worksheet.append(["East", 300])
    worksheet.append(["West", 500])
    excel_buffer = io.BytesIO()
    workbook.save(excel_buffer)
    sheet = extract("sales.xlsx", excel_buffer.getvalue())

    code = text_file("service.py", "def total_cost(price, quantity):\n    return price * quantity\n")
    longtext = text_file("notes.txt", "Introductory note. " * 500 + "\nThe central finding is that wetlands recovered after restoration.\n" + "Closing note. " * 650)
    brief = text_file("project-brief.txt", "Project Lighthouse\nOwner: Elena Morgan\nLaunch date: 14 March 2027\nThe project delivers a coastal monitoring dashboard.")
    other_person = text_file("other.txt", "Liam Chen attended Southport College and graduated in 2021.")
    other_report = text_file("other-report.txt", "Project Orchard is owned by Ravi and launches in 2029.")

    cases = [
        ("business_owner", [brief], "Who owns Project Lighthouse?", ["elena"], []),
        ("business_date", [brief], "When is Project Lighthouse launching?", ["2027"], []),
        ("skills", [resume], "List all skills in the resume.", ["java", "python", "kafka", "mockito"], []),
        ("graduation", [resume], "When did Maya graduate?", ["2023"], []),
        ("college", [resume], "Which college did Maya attend?", ["northbridge"], []),
        ("code", [code], "What does total_cost return?", ["price", "quantity"], []),
        ("spreadsheet", [sheet], "Sum Revenue in Sales", ["800"], []),
        ("missing", [resume], "What is Maya Rao passport number?", [], []),
        ("summary", [longtext], "Summarize the attachment.", ["wetland"], []),
        ("followup", [resume, other_person], "Which college did she attend?", ["northbridge"], ["When did Maya Rao graduate?"]),
        ("ambiguous", [resume], "What is the secret launch code?", [], []),
        ("report_followup", [brief, other_report], "When is it launching?", ["2027"], ["Who owns Project Lighthouse?"]),
    ]
    failures = 0
    for name, documents, question, expected, previous in cases:
        request_data = {"question": question, "documents": documents, "previousQuestions": previous}
        request = urllib.request.Request(
            base + "/api/ask", data=json.dumps(request_data).encode(),
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                result = json.load(response)
            answer = result.get("text", "")
            passed = all(word in answer.lower() for word in expected)
            if name in ("missing", "ambiguous"):
                missing_phrases = ("not present", "not provided", "not mentioned", "no information", "not available", "not contain", "does not", "cannot")
                passed = result.get("mode") == "no-match" or any(phrase in answer.lower() for phrase in missing_phrases)
            print(json.dumps({"case": name, "pass": passed, "mode": result.get("mode"), "answer": answer}))
            failures += not passed
        except Exception as error:
            print(json.dumps({"case": name, "pass": False, "error": str(error)}))
            failures += 1
    print(json.dumps({"passed": len(cases) - failures, "total": len(cases)}))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
