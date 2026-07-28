[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RepositoryRoot
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$verificationFiles = @(
    'gradle/verification-metadata.xml',
    'minecraft/fabric-1.21.1/gradle/verification-metadata.xml',
    'minecraft/forge-1.20.1/gradle/verification-metadata.xml',
    'minecraft/neoforge-1.21.1/gradle/verification-metadata.xml'
)
$fabricRelative = 'minecraft/fabric-1.21.1/gradle/verification-metadata.xml'
$canonicalManifestRelative = 'minecraft/fabric-1.21.1/gradle/fabric-canonical-artifacts.json'
$mappingReason = 'Loom 1.17.17 locally generates this exact mapping JAR with variable ZIP timestamps; mappings.tiny content is stable'
$remapReason = 'Loom 1.17.17 locally remaps this exact pinned Fabric artifact; ZIP timestamps vary'
$remoteFabricReason = 'Exact signed Fabric API JAR uses pinned canonical entry content because repository ZIP timestamps vary'

function Get-Attributes {
    param([System.Xml.XmlElement]$Node)
    $attributes = @{}
    foreach ($attribute in $Node.Attributes) {
        if ($attribute.NamespaceURI -eq 'http://www.w3.org/2000/xmlns/') {
            continue
        }
        $attributes[$attribute.Name] = $attribute.Value
    }
    return $attributes
}

function Test-ExactGeneratedTrust {
    param(
        [hashtable]$Attributes,
        [hashtable]$AllowedCoordinates
    )
    $requiredNames = @('group', 'name', 'version', 'file', 'reason')
    $actualNames = @($Attributes.Keys | Sort-Object) -join ','
    $expectedNames = @($requiredNames | Sort-Object) -join ','
    $expectedNamesWithRegex = @($requiredNames + 'regex' | Sort-Object) -join ','
    if ($actualNames -ne $expectedNames -and $actualNames -ne $expectedNamesWithRegex) {
        return $false
    }
    if ($Attributes.ContainsKey('regex') -and $Attributes.regex -ne 'false') {
        return $false
    }
    $key = '{0}:{1}:{2}:{3}' -f $Attributes.group, $Attributes.name, $Attributes.version, $Attributes.file
    if (-not $AllowedCoordinates.ContainsKey($key)) {
        return $false
    }
    return $Attributes.reason -eq $AllowedCoordinates[$key]
}

$documents = @{}
foreach ($relative in $verificationFiles) {
    $path = Join-Path $RepositoryRoot $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing dependency verification metadata: $relative"
    }
    [xml]$document = Get-Content -LiteralPath $path -Raw -Encoding UTF8
    $documents[$relative] = $document
}

$fabric = $documents[$fabricRelative]
$namespace = New-Object System.Xml.XmlNamespaceManager($fabric.NameTable)
$namespace.AddNamespace('v', 'https://schema.gradle.org/dependency-verification')

$allowed = @{}
$mappingKey = 'loom:mappings:layered+hash.2198:mappings-layered+hash.2198.jar'
$allowed[$mappingKey] = $mappingReason

$generatedComponents = $fabric.SelectNodes(
    '//v:component[@group="remapped.net.fabricmc.fabric-api" or (@group="net.minecraft" and starts-with(@name,"minecraft-merged-"))]',
    $namespace
)
foreach ($component in $generatedComponents) {
    foreach ($artifact in $component.SelectNodes('v:artifact[substring(@name,string-length(@name)-3)=".jar"]', $namespace)) {
        $expectedFile = '{0}-{1}.jar' -f $component.name, $component.version
        if ($artifact.name -ne $expectedFile) {
            throw "Unexpected Loom generated artifact filename: $($component.group):$($component.name):$($component.version):$($artifact.name)"
        }
        $key = '{0}:{1}:{2}:{3}' -f $component.group, $component.name, $component.version, $artifact.name
        if ($allowed.ContainsKey($key)) {
            throw "Duplicate generated artifact coordinate: $key"
        }
        $allowed[$key] = $remapReason
    }
}

if ($generatedComponents.Count -ne 51) {
    throw "Expected 51 exact Loom remapped components, found $($generatedComponents.Count)"
}

$canonicalManifestPath = Join-Path $RepositoryRoot $canonicalManifestRelative
if (-not (Test-Path -LiteralPath $canonicalManifestPath -PathType Leaf)) {
    throw "Missing canonical Fabric artifact manifest: $canonicalManifestRelative"
}
$canonicalManifest = Get-Content -LiteralPath $canonicalManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($canonicalManifest.schemaVersion -ne 1 -or
    $canonicalManifest.canonicalization -ne 'sorted-entry-record-sha256-v1' -or
    $canonicalManifest.fabricCertificateSha256 -notmatch '^[a-f0-9]{64}$') {
    throw 'Invalid canonical Fabric artifact manifest header'
}
if ($canonicalManifest.artifacts.Count -ne 47) {
    throw "Expected 47 canonical Fabric artifacts, found $($canonicalManifest.artifacts.Count)"
}
$canonicalKeys = @{}
foreach ($artifact in $canonicalManifest.artifacts) {
    $propertyNames = @($artifact.PSObject.Properties.Name | Sort-Object) -join ','
    if ($propertyNames -ne 'canonicalSha256,file,group,name,version' -or
        $artifact.group -ne 'net.fabricmc.fabric-api' -or
        $artifact.canonicalSha256 -notmatch '^[a-f0-9]{64}$' -or
        $artifact.file -ne "$($artifact.name)-$($artifact.version).jar") {
        throw "Invalid canonical Fabric artifact row: $($artifact | ConvertTo-Json -Compress)"
    }
    $key = '{0}:{1}:{2}:{3}' -f $artifact.group, $artifact.name, $artifact.version, $artifact.file
    if ($canonicalKeys.ContainsKey($key)) {
        throw "Duplicate canonical Fabric artifact row: $key"
    }
    $canonicalKeys[$key] = $true
    $component = $fabric.SelectSingleNode(
        "//v:component[@group='$($artifact.group)' and @name='$($artifact.name)' and @version='$($artifact.version)']/v:artifact[@name='$($artifact.file)']",
        $namespace
    )
    if ($null -eq $component) {
        throw "Canonical Fabric artifact is absent from dependency verification metadata: $key"
    }
    $allowed[$key] = $remoteFabricReason
}

$alsoTrustNodes = $fabric.SelectNodes('//v:also-trust', $namespace)
if ($alsoTrustNodes.Count -ne 0) {
    throw "Fixed checksum accumulation is forbidden for timestamp-variable Fabric artifacts; found $($alsoTrustNodes.Count) also-trust nodes"
}

$actual = @{}
foreach ($relative in $verificationFiles) {
    [xml]$document = $documents[$relative]
    $manager = New-Object System.Xml.XmlNamespaceManager($document.NameTable)
    $manager.AddNamespace('v', 'https://schema.gradle.org/dependency-verification')
    $trustNodes = $document.SelectNodes('//v:configuration/v:trusted-artifacts/v:trust', $manager)
    if ($relative -ne $fabricRelative -and $trustNodes.Count -ne 0) {
        throw "Trusted artifacts are forbidden outside the Fabric Loader metadata: $relative"
    }
    foreach ($node in $trustNodes) {
        $attributes = Get-Attributes $node
        if (-not (Test-ExactGeneratedTrust -Attributes $attributes -AllowedCoordinates $allowed)) {
            throw "Dependency verification trust is broader than the exact Loom generated allow-list: $relative $($node.OuterXml)"
        }
        $key = '{0}:{1}:{2}:{3}' -f $attributes.group, $attributes.name, $attributes.version, $attributes.file
        if ($actual.ContainsKey($key)) {
            throw "Duplicate dependency verification trust: $key"
        }
        $actual[$key] = $true
    }
}

$missing = @($allowed.Keys | Where-Object { -not $actual.ContainsKey($_) } | Sort-Object)
$unexpected = @($actual.Keys | Where-Object { -not $allowed.ContainsKey($_) } | Sort-Object)
if ($missing.Count -ne 0 -or $unexpected.Count -ne 0) {
    throw "Exact Loom generated trust set mismatch. Missing=[$($missing -join ';')] Unexpected=[$($unexpected -join ';')]"
}

$negativeFixtures = @(
    @{ group = 'loom'; reason = 'group-wide trust' },
    @{ group = 'loom'; name = 'mappings'; version = '.*'; file = '.*[.]jar'; regex = 'true'; reason = $mappingReason },
    @{ group = 'com.example'; name = 'external'; version = '1.0'; file = 'external-1.0.jar'; regex = 'false'; reason = 'external artifact' }
)
foreach ($fixture in $negativeFixtures) {
    if (Test-ExactGeneratedTrust -Attributes $fixture -AllowedCoordinates $allowed) {
        throw "Dependency verification negative fixture was accepted: $($fixture | ConvertTo-Json -Compress)"
    }
}

Write-Output "Dependency verification trust check passed: $($actual.Count) exact JARs, including 47 signed canonical Fabric artifacts; also-trust accumulation, group, regex and external-artifact negative fixtures rejected."
