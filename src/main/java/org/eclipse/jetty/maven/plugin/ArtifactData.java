package org.eclipse.jetty.maven.plugin;

public class ArtifactData
{
    public String groupId;
    public String artifactId;
    public String version;
    public String classifier;
    public String type;
    public String scope;

    @Override
    public String toString() {
        return "ArtifactData{" +
                "groupId='" + groupId + '\'' +
                ", artifactId='" + artifactId + '\'' +
                ", version='" + version + '\'' +
                ", classifier='" + classifier + '\'' +
                ", scope='" + scope + '\'' +
                ", type='" + type + '\'' +
                '}';
    }
}