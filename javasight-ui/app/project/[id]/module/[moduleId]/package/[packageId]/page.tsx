'use client';

import { useEffect, useState, use } from 'react';
import { DocumentDuplicateIcon, CodeBracketIcon } from '@heroicons/react/24/outline';
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
  }[];
  metrics: {
    fileCount: number;
    linesOfCode: number;
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
            <h1 className="text-2xl font-bold mb-4">{packageDetails.packageName}</h1>
            <div className="prose prose-sm max-w-none mb-6">
              <ReactMarkdown>{packageDetails.description}</ReactMarkdown>
            </div>
            
            <div className="grid grid-cols-2 gap-4">
              <div className="flex items-center gap-2">
                <DocumentDuplicateIcon className="h-5 w-5 text-gray-500" />
                <span className="text-gray-600">{packageDetails.fileCount} files</span>
              </div>
              <div className="flex items-center gap-2">
                <CodeBracketIcon className="h-5 w-5 text-gray-500" />
                <span className="text-gray-600">{packageDetails.linesOfCode} lines of code</span>
              </div>
            </div>
          </div>

          <div className="bg-white rounded-lg shadow-sm p-6">
            <h2 className="text-xl font-semibold mb-4">Files</h2>
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-200">
                <thead>
                  <tr>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">File Name</th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Lines of Code</th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Description</th>
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
                  {packageDetails.files.map((file, index) => (
                    <tr key={index}>
                      <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900">{file.name}</td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{file.linesOfCode}</td>
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