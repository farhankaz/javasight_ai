'use client';

import { DocumentDuplicateIcon, ChatBubbleLeftIcon, CodeBracketIcon, FolderIcon } from '@heroicons/react/24/outline';
import ReactMarkdown from 'react-markdown';
import { useState } from 'react';
import { useRouter } from 'next/navigation';

interface PackageCardProps {
  name: string;
  files: number;
  linesOfCode: number;
  description: string;
  projectId: string;
  moduleId: string;
  packageId: string;
}

export default function PackageCard({ name, files, linesOfCode, description, projectId, moduleId, packageId }: PackageCardProps) {
  const [showFullDescription, setShowFullDescription] = useState(false);
  const router = useRouter();

  const handleClick = () => {
    if (!projectId || !moduleId || !packageId) {
      console.error('Missing required IDs for navigation:', { projectId, moduleId, packageId });
      return;
    }
    router.push(`/project/${projectId}/module/${moduleId}/package/${packageId}`);
  };

  const handleMoreClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    setShowFullDescription(true);
  };

  const handleLessClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    setShowFullDescription(false);
  };

  const truncateDescription = (text: string = '') => {
    if (!text || text.length <= 100) return text || '';
    return `${text.slice(0, 100).trim()}... `;
  };

  return (
    <div 
      className="bg-white rounded-lg shadow-sm p-6 cursor-pointer hover:shadow-md transition-shadow"
      onClick={handleClick}
    >
      <div className="flex justify-between items-start mb-4">
        <div>
          <div className="flex items-center gap-2">
            <FolderIcon className="w-6 h-6 text-indigo-500" />
            <h3 className="text-xl font-semibold text-gray-900">{name}</h3>
          </div>
          <div className="prose prose-sm max-w-none mt-1 text-gray-500">
            {showFullDescription ? (
              <div>
                <ReactMarkdown>{description}</ReactMarkdown>
                <button
                  onClick={handleLessClick}
                  className="text-blue-600 hover:text-blue-800 text-sm font-medium"
                >
                  Show less
                </button>
              </div>
            ) : (
              <div>
                <span>{truncateDescription(description)}</span>
                {description.length > 100 && (
                  <button
                    onClick={handleMoreClick}
                    className="text-blue-600 hover:text-blue-800 text-sm font-medium inline"
                  >
                    more
                  </button>
                )}
              </div>
            )}
          </div>
        </div>
        <ChatBubbleLeftIcon className="h-5 w-5 text-gray-400 cursor-pointer hover:text-gray-600" />
      </div>
      <div className="flex items-center gap-2 text-gray-500 text-sm mb-2">
        <DocumentDuplicateIcon className="h-4 w-4" />
        <span>{files} files</span>
      </div>
      <div className="flex items-center text-gray-600 text-sm">
        <CodeBracketIcon className="w-4 h-4 mr-2" />
        <span>{linesOfCode} lines of code</span>
      </div>
    </div>
  );
} 