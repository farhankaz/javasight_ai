'use client';

import { use, useEffect, useState } from 'react';
import Breadcrumb from '@/app/components/Breadcrumb';
import ProjectMetrics from '@/app/components/ProjectMetrics';
import ModuleCard from '@/app/components/ModuleCard';
import ReactMarkdown from 'react-markdown';

interface Module {
  _id: string;
  projectId: string;
  moduleName: string;
  modulePath: string;
  analysis: string;
  analysisDate: number;
  fileCount: number;
  linesOfCode: number;
  packageCount: number;
  metrics?: {
    packageCount: number;
    fileCount: number;
    linesOfCode: number;
  } | null;
}

interface Project {
  _id: string;
  projectName: string;
  projectLocation: string;
  analysis: string;
  analysisDate: number;
  fileCount: number;
  linesOfCode: number;
  moduleCount: number;
  packageCount: number;
  metrics: {
    moduleCount: number;
    packageCount: number;
    fileCount: number;
    linesOfCode: number;
  } | null;
  modules: Module[];
}

interface PageProps {
  params: Promise<{ id: string }>;
}

export default function ProjectPage({ params }: PageProps) {
  const [project, setProject] = useState<Project | null>(null);
  const [loading, setLoading] = useState(true);
  const [showFullDescription, setShowFullDescription] = useState(false);
  const resolvedParams = use(params);
  const id = resolvedParams.id;

  useEffect(() => {
    const fetchProject = async () => {
      try {
        const response = await fetch(`/api/projects/${id}`);
        if (!response.ok) throw new Error('Failed to fetch project');
        const data = await response.json();
        setProject(data);
      } catch (error) {
        console.error('Error fetching project:', error);
      } finally {
        setLoading(false);
      }
    };

    fetchProject();
  }, [id]);

  if (loading) {
    return <div className="container mx-auto px-4 py-8">Loading...</div>;
  }

  if (!project) {
    return <div className="container mx-auto px-4 py-8">Project not found</div>;
  }

  const truncateDescription = (text: string | undefined | null) => {
    if (!text) return '';
    if (text.length <= 100) return text;
    return `${text.slice(0, 100).trim()}... `;
  };

  return (
    <main className="flex-1">
      <div className="container mx-auto px-4 py-8 max-w-7xl">
        <div className="space-y-6">
        <Breadcrumb 
            projectId={resolvedParams.id}
          />

          <div>
            <h1 className="text-3xl font-bold text-gray-900">{project.projectName}</h1>
            <div className="prose prose-sm mt-2 text-gray-600 max-w-none">
              {showFullDescription ? (
                <div>
                  <ReactMarkdown>{project.analysis || ''}</ReactMarkdown>
                  <button
                    onClick={() => setShowFullDescription(false)}
                    className="text-blue-600 hover:text-blue-800 text-sm font-medium"
                  >
                    Show less
                  </button>
                </div>
              ) : (
                <div>
                  <span>{truncateDescription(project.analysis)}</span>
                  {project.analysis && project.analysis.length > 100 && (
                    <button
                      onClick={() => setShowFullDescription(true)}
                      className="text-blue-600 hover:text-blue-800 text-sm font-medium inline"
                    >
                      more
                    </button>
                  )}
                </div>
              )}
            </div>
          </div>

          <ProjectMetrics metrics={project.metrics} />

          <div>
            <h2 className="text-2xl font-semibold text-gray-900 mb-4">Modules</h2>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {(project.modules || []).map((module) => (
                <ModuleCard
                  key={module._id}
                  title={module.moduleName}
                  description={module.analysis}
                  projectId={id}
                  moduleId={module._id}
                  packageCount={module.metrics?.packageCount || 0}
                  fileCount={module.metrics?.fileCount || 0}
                  linesOfCode={module.metrics?.linesOfCode || 0}
                />
              ))}
            </div>
          </div>
        </div>
      </div>
    </main>
  );
} 