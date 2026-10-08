package dev.ppiankov.pockettag

// WO-8: each added type must retain compatibility with the complete pre-cluster schema.
internal const val PRE_CLUSTER_JSON = """[
    {"id":"old-link","type":"link","label":"Link","url":"https://example.com/"},
    {"id":"old-contact","type":"contact","label":"Contact","givenName":"Example",
     "familyName":"Person","org":"","title":"","phone":"","email":"","url":"","note":""},
    {"id":"old-whatsapp","type":"whatsapp","label":"Chat","number":"10000000000"},
    {"id":"old-call","type":"call","label":"Call","number":"+10000000000"},
    {"id":"old-email","type":"email","label":"Email","address":"sample@example.com","subject":null},
    {"id":"old-sms","type":"sms","label":"SMS","number":"+10000000000","body":null},
    {"id":"old-raw","type":"raw","label":"Saved link","uri":"geo:1,2"}
]"""
