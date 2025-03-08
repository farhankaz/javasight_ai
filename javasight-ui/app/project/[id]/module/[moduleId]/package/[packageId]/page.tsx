'use client';

import { useEffect, useState, use } from 'react';
import { DocumentDuplicateIcon, CodeBracketIcon, FolderIcon, SparklesIcon } from '@heroicons/react/24/outline';
import ReactMarkdown from 'react-markdown';
import Breadcrumb from '@/app/components/Breadcrumb';

interface PackageDetails {
  packageName: string;
  description: string;
  fileCount: number;
  linesOfCode: number;
  files: {
    name: string;
    linesOfCode: number;
    description: string;
    codeTokenCount: number;
    analysisTokenCount: number;
  }[];
  metrics: {
    fileCount: number;
    linesOfCode: number;
    combinedAnalysisTokenCount: number;
    codeTokenCount: number;
  };
}

export default function PackageDetailsPage({ 
  params 
}: { 
  params: Promise<{ id: string; moduleId: string; packageId: string }> 
}) {
  const resolvedParams = use(params);
  const [packageDetails, setPackageDetails] = useState<PackageDetails | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchPackageDetails = async () => {
      try {
        const details = await fetch(`/api/projects/${resolvedParams.id}/modules/${resolvedParams.moduleId}/packages/${resolvedParams.packageId}`).then(res => res.json());
        console.log(details);
        setPackageDetails(details);
      } catch (err) {
        setError(err.message);
      } finally {
        setLoading(false);
      }
    };

    fetchPackageDetails();
  }, [resolvedParams.id, resolvedParams.moduleId, resolvedParams.packageId]);

  if (loading) {
    return <div className="p-6">Loading...</div>;
  }

  if (error) {
    return <div className="p-6 text-red-500">Error: {error}</div>;
  }

  if (!packageDetails) {
    return <div className="p-6">Package not found</div>;
  }

  return (
    <main className="flex-1">
      <div className="container mx-auto px-4 py-8 max-w-7xl">    
        <div className="space-y-6">
          <Breadcrumb 
            projectId={resolvedParams.id}
            moduleId={resolvedParams.moduleId}
            packageId={resolvedParams.packageId}
          />

          <div className="bg-white rounded-lg shadow-sm p-6">
            <div className="flex items-center gap-2 mb-4">
              <FolderIcon className="w-6 h-6 text-indigo-500" />
              <h1 className="text-xl font-bold">Package: {packageDetails.packageName}</h1>
            </div>
            <div className="prose prose-sm max-w-none mb-6">
              <ReactMarkdown>{packageDetails.description}</ReactMarkdown>
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <div className="flex items-center gap-1">
                <DocumentDuplicateIcon className="h-5 w-5 text-gray-500" />
                <h3 className="text-sm font-medium text-gray-500">Files</h3>
              </div>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{packageDetails.metrics?.fileCount.toLocaleString() || 0}</p>
            </div>
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <div className="flex items-center gap-1">
                <CodeBracketIcon className="h-5 w-5 text-gray-500" />
                <h3 className="text-sm font-medium text-gray-500">Lines of Code</h3>
              </div>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{packageDetails.metrics?.linesOfCode.toLocaleString() || 0}</p>
            </div>
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <div className="flex items-center gap-1">
                <SparklesIcon className="h-5 w-5 text-gray-500" />
                <h3 className="text-sm font-medium text-gray-500">Analysis Tokens</h3>
              </div>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{packageDetails.metrics?.combinedAnalysisTokenCount?.toLocaleString() || 0}</p>
            </div>
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <div className="flex items-center gap-1">
                <SparklesIcon className="h-5 w-5 text-gray-500" />
                <h3 className="text-sm font-medium text-gray-500">Code Tokens</h3>
              </div>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{packageDetails.metrics?.codeTokenCount?.toLocaleString() || 0}</p>
            </div>
          </div>

          <div className="bg-white rounded-lg shadow-sm p-6">
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-200">
                <thead>
                  <tr>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">File Name</th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Lines of Code</th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Analysis Tokens</th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Code Tokens</th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Description</th>
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
                  {packageDetails.files.map((file, index) => (
                    <tr key={index}>
                      <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900">{file.name}</td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{file.linesOfCode}</td>
                      <td className="px-6 py-4 text-sm text-gray-500">{file.analysisTokenCount}</td>
                      <td className="px-6 py-4 text-sm text-gray-500">{file.codeTokenCount}</td>
                      <td className="px-6 py-4 text-sm text-gray-500">{file.description}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </main>
  );
} 